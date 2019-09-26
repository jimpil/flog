(ns flog.util
  (:import (java.util.concurrent.locks ReentrantLock)
           (java.io Writer PushbackReader File)
           (java.net InetAddress)
           (java.time ZoneId Instant)
           (java.util.concurrent ExecutorService ThreadFactory Executors TimeUnit)
           (java.nio.file Files StandardCopyOption FileSystems WatchService StandardWatchEventKinds WatchKey WatchEvent Path ClosedWatchServiceException Paths)
           (java.util.concurrent.atomic AtomicLong)
           (java.util ResourceBundle UUID)
           (java.nio.file.attribute FileAttribute)))

(defonce ^ZoneId  utc-tz
  (ZoneId/of "Z")) ;; shorthand for UTC

(def user-tz
  (delay
    (ZoneId/of ;; get this property at runtime and cache it
      (System/getProperty "user.timezone"))))

(def os-info
  (delay {:name    (System/getProperty "os.name")
          :version (System/getProperty "os.version")}))

(def host-info
  (delay {:local    (str (InetAddress/getLocalHost))
          :loopback (str (InetAddress/getLoopbackAddress))}))

(def java-info
  (delay
    {:version (System/getProperty "java.version")
     :vendor  (System/getProperty "java.vendor")}))

(defn base-info
  []
  (let [now-utc (Instant/now)
        thread  (Thread/currentThread)
        ^ZoneId user-tz @user-tz]
    {:uuid (str (UUID/randomUUID)) ;; unique identifier per event
     :thread {:name (.getName thread)
              :id   (.getId thread)
              :priority (.getPriority thread)}
     ;; in my experience both timestamps are valuable when debugging logs
     ;; it's a pretty cheap call to convert anyway
     :timestamp {:utc   (str now-utc)
                 :local (str (.atZone now-utc user-tz))}
     :os   @os-info
     :java @java-info
     :host @host-info}))

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
                (thread-pool :solo nil factory)
                (Executors/newFixedThreadPool n-threads factory))
       :cached (Executors/newCachedThreadPool factory)
       :solo (Executors/newSingleThreadExecutor factory)))))

(defonce #^"[Ljava.nio.file.StandardCopyOption;" move-opts
  (into-array [StandardCopyOption/ATOMIC_MOVE]))

(defonce #^"[Ljava.lang.String;" empty-string-array
  (make-array String 0))

(defonce #^"[Ljava.nio.file.attribute.FileAttribute;" empty-fattrs
  (make-array FileAttribute 0))

(defn backup-file!
  [^String source ^String target]
  (let [source-path (Paths/get source empty-string-array)
        target-path (Paths/get target empty-string-array)]
    (Files/move source-path target-path move-opts)
    (Files/createFile source-path empty-fattrs)))

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

#_(defn rate-limited
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

(defn bundle->map
  [^ResourceBundle bundle]
  (when-some [ks (some-> bundle .getKeys enumeration-seq not-empty)]
    (zipmap ks
            (map (fn [k]
                   (.getString bundle k))
                 ks))))