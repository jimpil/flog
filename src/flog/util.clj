(ns flog.util
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (java.util.concurrent.locks ReentrantLock)
           (java.io Writer PushbackReader File)
           (java.net InetAddress)
           (java.time LocalDateTime)
           (java.util.concurrent ExecutorService ThreadFactory Executors)
           (java.nio.file Files StandardCopyOption FileSystems WatchService StandardWatchEventKinds WatchKey WatchEvent Path ClosedWatchServiceException)))

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
