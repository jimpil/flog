(ns flog.util
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (java.util.concurrent.locks ReentrantLock)
           (java.io Writer PushbackReader File)
           (java.net InetAddress)
           (java.time LocalDateTime)
           (java.util.concurrent ExecutorService ThreadFactory Executors TimeUnit)
           (java.nio.file Files StandardCopyOption FileSystems WatchService StandardWatchEventKinds WatchKey WatchEvent Path ClosedWatchServiceException)
           (java.util.concurrent.atomic AtomicLong)))

(defn env-info
  []
  {:thread    (.getName (Thread/currentThread))
   :host      (str (InetAddress/getLocalHost))
   :issued-at (str (LocalDateTime/now))})


(defmacro while-let
  "Makes it easy to continue processing an expression as long as it is true"
  [binding & forms]
  `(loop []
     (when-let ~binding
       ~@forms
       (recur))))

(defn wr-str
  "Like `pr-str`, but prints to <w>."
  [^Writer w x]
  (if (string? x)
    (.write w ^String x)
    (binding [*out* w]
      (pr x))))

(defmacro with-lock
  ""
  [lock-expr & body]
  `(let [lockee# ~(with-meta lock-expr {:tag 'java.util.concurrent.locks.ReentrantLock})]
     (.lock lockee#)
     (try ~@body
          (finally
            (.unlock lockee#)))))

(defmacro with-try-lock
  "Same as `with-lock`, but uses `tryLock()` instead."
  [lock-expr & body]
  `(let [lockee# ~(with-meta lock-expr {:tag 'java.util.concurrent.locks.ReentrantLock})]
     (and (.tryLock lockee#)
          (try ~@body
               (finally
                 (.unlock lockee#))))))

(defn chaining
  "Helper for composing loggers in an order that makes sense
   (i.e. the order in which they will be called)."
  [logger & wrappers]
  (reduce
    (fn [logger wrapping]
      (wrapping logger))
    logger
    (reverse wrappers)))

(defn symbol->obj [sym]
  (-> sym requiring-resolve var-get))

(defn thread-pool
  (^ExecutorService [named-type n-threads]
   (thread-pool named-type n-threads (Executors/defaultThreadFactory)))
  (^ExecutorService [named-type n-threads factory]
   (let [^ThreadFactory factory factory]
     (case named-type
       :scheduled (Executors/newScheduledThreadPool n-threads factory)
       :scheduled-solo (Executors/newSingleThreadScheduledExecutor factory)
       :fixed (if (= 1 n-threads)
                (thread-pool :solo n-threads factory)
                (Executors/newFixedThreadPool n-threads factory))
       :cached (Executors/newCachedThreadPool factory)
       :solo (Executors/newSingleThreadExecutor factory)))))

(defonce move-opts
  (into-array [StandardCopyOption/ATOMIC_MOVE]))

(defn move-file!
  [^File source ^File target]
  (Files/move (.toPath source)
              (.toPath target)
              move-opts))

(defn start-watching-file!
  [handler ^File f]
  (let [dir-to-watch (.toPath (.getParentFile f))
        watcher (.newWatchService (FileSystems/getDefault))]
    (.register dir-to-watch watcher StandardWatchEventKinds/ENTRY_MODIFY)
    (future
      (try
        (while-let [wk (.take watcher)]
          (doseq [^WatchEvent e (.pollEvents wk)]
            (let [kind (.kind e)
                  path-modified (.context e)]
              (when (and (= kind StandardWatchEventKinds/ENTRY_MODIFY);; ignore OVERFLOW events
                         (= (.toPath f) path-modified))
                (handler path-modified))))
          (when (false? (.reset wk))
            ;; directory is inaccessible so abort the loop!
            (.close watcher)))
        (catch InterruptedException _ nil)
        (catch ClosedWatchServiceException _ nil)) ;; what to do here?!

      )
    )
  )

(defn rate-limited
  "Returns a function which wraps `f`. That fn can only be called
   <n-calls> times during the specified <time-unit> (java.util.concurrent.TimeUnit).
   Any further calls will return nil without calling <f> (noop). As soon as the specified
   <time-unit> passes, new calls will start calling <f> again."

  ([f rate]
   (rate-limited f rate (constantly nil)))
  ([f [n-calls ^TimeUnit time-unit n-units :as rate] log-fn]
   (let [times-called (AtomicLong. 0) ;; not been called yet
         nanos-per-unit (.toNanos time-unit (or n-units 1))
         t (AtomicLong. 0)
         do-f (fn [args]
                (try (apply f args)
                     (finally
                       (log-fn (.incrementAndGet times-called))))) ;; go from n => n+1
         ]

     (fn [& args]
       (let [previous-time* (.get t)
             calls-so-far (.get times-called)
             first-time? (zero? previous-time*) ;; this will be true the very first time only
             now (System/nanoTime)
             previous-time (if first-time?
                             ;; very first time being called - initialise it properly
                             (.addAndGet t now)
                             previous-time*)
             time-elapsed (- now previous-time)]
         ;(println time-elapsed)

         (if (>= time-elapsed nanos-per-unit)
           (do ;; reset everything
             ;(println "resetting everything...")
             (.set times-called 0)
             (.set t now)
             ;; don't forget to call <f>
             (do-f args))

           (when (> n-calls calls-so-far) ;; call count starts at 0 so use `>`
             ;; all good -call <f>
             (do-f args))
           )
         )
       )
     )
   )
  )


(defn agent-cb
  "Returns a vector of two elements `[agent wrapper cbs-atom]`.
   <agent>    - agent implementing circuit-breaking semantics
                (see https://docs.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker)
   <wrapper>  - function to call with your send-fn as its argument - returns the correct fn to send to <agent>
   <cbs-atom> - an atom holding the internal state of the circuit-breaker (4 keys). Super useful for debugging/testing.
                Should only ever be `deref`-ed (NEVER changed)!

   Requires the following arguments:

  <init-state>    - The initial state of the agent (will contain an extra key `::cbs` - ignore it in your app's logic)
  <fail-limit>    - How many Exceptions (within <fail-interval>) before transitioning from CLOSED => OPEN
  <fail-interval> - Time window in which <fail-limit> has an effect
  <success-limit> - How many successful calls before transitioning from HALF-OPEN => CLOSED
  <open-timeout>  - How long to wait before transitioning from OPEN => HALF-OPEN
  <drop-fn>       - Fn to handle all requests while in OPEN state (arg-list per the fn you will wrap with <wrapper>).
                    If a default value makes sense in your domain this is your chance to use it.
                    Can be used for logging.
  <ex-fn>         - Fn of 3 args to be called last in agent's error-handler. Takes the Exception itself,
                    the time it occurred (per `System/nanoTime`) & the current fail count.
                    Can be used for logging.

  Limitations/advice:

  - You can/should NOT change the error-handler, nor the error-mode of the returned agent.
  - You can/should NOT set a validator to the returned agent, as it will interfere with the error-handler.
  - The agent returned already carries some metadata. Make sure they don't get lost if you want to attach your own.
  - De-structure the returned vector as `[agent cb-wrap]` (i.e. ignore the third element)."
  [init-state [fail-limit fail-interval success-limit] open-timeout drop-fn ex-fn]
  (let [CBS (atom {:cbs :CLOSED ;; circuit-breaker initial state
                   :success-count 0
                   :fail-count 0
                   :last-fail 0}) ;; piece of state to allow for fail-interval
        time-window-ns (* fail-interval 1000000) ;; we want nanos
        error-handler (fn cb-error-handler [_a ex]
                        (let [error-time (System/nanoTime)
                              previous-fail (:last-fail @CBS)
                              {:keys [fail-count cbs]} (swap! CBS #(-> %
                                                                       (assoc :last-fail error-time)
                                                                       (update :fail-count inc)))]
                          (when (and (not= :OPEN cbs)           ;; someone else has already done this!
                                     (>= fail-count fail-limit) ;; over the fail-limit
                                     (or (zero? previous-fail)  ;; check for interval only when it makes sense
                                         (>= time-window-ns (- error-time previous-fail)))) ;; within window?
                            ;; transition to OPEN immediately,
                            ;; and to HALF-OPEN after <open-timeout> ms
                            (swap! CBS assoc :cbs :OPEN)
                            (future
                              (Thread/sleep open-timeout)
                              (swap! CBS assoc
                                     :cbs :HALF-OPEN
                                     :success-count 0))) ;; don't forget to reset this!
                          ;; let the caller decide how to handle exceptions
                          (ex-fn ex error-time fail-count)))

        ag (agent init-state
                  :meta {:circuit-breaker? true} ;; useful meta (see `print-method` for `OnAgent` record)
                  ;; providing an error-handler returns an agent with error-mode => :continue (never needs restarting)
                  :error-handler error-handler)
        ag-f (fn cb-send-fn [process]
               (fn [curr-state & args]
                 (case (:cbs @CBS)
                   ;; circuit is closed - current flows through
                   :CLOSED (apply process curr-state args) ;; state may change depending on `process`
                   ;; circuit is open - current does not flow through
                   :OPEN   (do (apply drop-fn curr-state args)
                               curr-state) ;; state remains unchanged
                   ;; circuit is half-open - try to push some current through
                   :HALF-OPEN (try
                                (let [ret (apply process curr-state args) ;; this is the critical call
                                      {:keys [success-count]} (swap! CBS update :success-count inc)]
                                  (when (>= success-count success-limit) ;; assume the service was fixed
                                      ;; we're about to go back to normal operation (CLOSED state)
                                      ;; reset all state to how they originally were (except success-count obviously)
                                      (swap! CBS assoc
                                             :cbs :CLOSED
                                             :fail-count 0
                                             :last-fail 0))
                                    ret)
                                (catch Throwable t
                                  ;; re-throw the exception after making sure that
                                  ;; the error-handler will transition the state to OPEN
                                  ;; on the very first attempt. That means resetting `last-fail`
                                  ;; but NOT `fail-count`!
                                  (swap! CBS assoc :last-fail 0)
                                  (throw t))))))]
    ;; it is advised to ignore the last element
    ;; destructure like `[agnt cb-wrap]` for example
    [ag ag-f CBS]))