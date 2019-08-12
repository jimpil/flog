(ns flog.loggers
  (:require [flog.internal :as proto]
            [flog.util :as ut]
            [flog.appenders :as appenders]
            [clojure.java.io :as io]
            [clojure.pprint :refer [cl-format]]
            [flog.log-levels :as levels]
            [flog.internal :as internal])
  (:import [flog.internal ILogger]
           (java.util.concurrent ConcurrentLinkedQueue ExecutorService TimeUnit Executors)
           (java.util.concurrent.locks ReentrantLock)
           (java.io Writer File)
           (java.util.concurrent.atomic AtomicLong)
           (java.time LocalDate LocalTime)
           (java.time.temporal ChronoUnit)
           (clojure.lang IFn)))


(defrecord XLogger [level f logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ e]
    (f logger e)))

(defmethod print-method XLogger [this ^Writer w]
  (.write w "#flog.internal.XLogger")
  (.write w (pr-str
              (merge (meta this)
                     (select-keys this [:level :logger])))))

(defn xlogger
  "Composes <logger> with the NON-stateful transducer <xform>.
   Returns a new Logger, which will delegate
   to the one provided after having xformed the input."
  ([level xf f logger]
   (with-meta
     (xlogger level
              ((cond-> xf (symbol? xf) ut/symbol->obj)
               (cond-> f (symbol? f) ut/symbol->obj))
              logger)
     {:f f :xf xf}))
  ([level xform logger]
   (let [f (xform proto/xlog)]
     (XLogger. level f logger))))

(defn map->XLogger
  [m]
  (xlogger (:level m)
           (:xf m)
           (:f m)
           (:logger m)))


;; LOW-LEVEL LOGGERS
;; =================

(defn mapping
  ([f logger]
   (mapping (proto/getLevel logger) f logger))
  ([level f logger]
   (levels/ensure-valid-level! level)
   (xlogger level 'clojure.core/map f logger)))

(defn map->Mapping
  [m]
  (mapping (:level m)
           (:fn m) ;; must be fully-qualified
           (:logger m)))


(defn filtering
  ([f logger]
   (filtering (proto/getLevel logger) f logger))
  ([level f logger]
   (levels/ensure-valid-level! level)
   (xlogger level 'clojure.core/filter f logger)))

(defn map->Filtering
  [m]
  (filtering (:level m)
             (:fn m)
             (:logger m)))

(defn removing
  ([f logger]
   (removing (proto/getLevel logger) f logger))
  ([level f logger]
   (levels/ensure-valid-level! level)
   (xlogger level 'clojure.core/remove f logger)))

(defn map->Removing
  [m]
  (removing (:level m)
            (:fn m)
            (:logger m)))

;; JUNCTION/BRANCHING LOGGER
;; =========================

(defn enabled-for-logger?
  "Rule per Logback's basic selection rule:
   A log request of level p issued to a logger having
   an effective level q, is enabled if p >= q."
  [logger parent-level event]
  (>= (levels/priority (:level event))
      (levels/priority (or (proto/getLevel logger)
                           parent-level))))

(defrecord Branching [level loggers]
  ILogger
  (getLevel [_] level)
  (log [this e]
    (run!
      #(and (enabled-for-logger? % level e)
            (proto/xlog % e))
      loggers))

  IFn ;; Branching loggers are `add-tap` friendly
  (invoke [this x]
    (internal/xlog this x))
  )

(defn branching
  "Returns a Logger which delegates to all
   provided <loggers> in sequence (via `run!`)."
  ([loggers]
   (branching levels/TRACE loggers))
  ([level loggers]
   (levels/ensure-valid-level! level)
   (Branching. level loggers)))

(defn map->Branching
  [m]
  (if-let [rlevel (:level m)]
    (branching rlevel (:loggers m))
    (branching (:loggers m))))

;; LOCKING VARIANTS
;; =================
(defrecord Locking [level lock logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ e]
    (ut/with-lock lock (proto/xlog logger e))))

(defmethod print-method Locking [this ^Writer w]
  (.write w "#flog.loggers.Locking")
  (.write w (pr-str (dissoc this :lock))))

(defn with-locking ;; don't want to name it `locking` and shadow `clojure.core/locking`
  "Returns a Logger which delegates to the provided one
   after acquiring a lock."
  ([logger]
   (with-locking (proto/getLevel logger) logger))
  ([level logger]
   (levels/ensure-valid-level! level)
   (let [lock (ReentrantLock.)]
     (Locking. level lock logger))))

(defn map->Locking ;; shadow the original
  [m]
  (with-locking (:level m) (:logger m)))

(defn locking-console
  "A general-purpose console appender based on locking."
  ([]
   (locking-console nil))
  ([level]
   (with-locking level (appenders/std-out level))))

(defn map->LockingConsole
  [m]
  (locking-console (:level m)))


(defn locking-file
  ([f]
   (locking-file nil f))
  ([level ^File f]
   (with-locking level (appenders/with-open-writer level f))))

(defn map->LockingFile
  [m]
  (locking-file (:level m) (:file m)))

;; BATCHING VARIANTS
;; =================
(defrecord Batching [level out lock ^ConcurrentLinkedQueue pending logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ event]
    (.offer pending event) ;; always true
    (ut/with-try-lock lock
      (with-open [wrt (io/writer out :append true)]
        ;; keep writing from this thread
        ;; for as long as there are elements
        (ut/while-let [e (.poll pending)]
          (proto/log logger wrt e))
        (.flush wrt)))))

(defn batching
  "Returns a logger backed by a ConcurrentLinkedQueue.
   Messages are queued and written out in bursts via <logger>,
   which MUST implement the 3-arg arity of ILogger.
   <out> per `io/writer`."
  ([out logger]
   (batching (proto/getLevel logger) out logger))
  ([level out logger]
   (levels/ensure-valid-level! level)
   (let [lock    (ReentrantLock.)
         pending (ConcurrentLinkedQueue.)]
     (Batching. level out lock pending logger))))

(defn map->Batching
  [m]
  (batching (:level m)
            (:out m)
            (:logger m)))


(defn batching-file
  "High-performance & thread-safe file-logger
   which queues the producers & batches the consumers
   in order to minimise writer open/close."
  ([f]
   (batching-file nil f))
  ([level ^File f]
   (batching level f (appenders/pass-through-writer level))))

(defn map->BatchingFile
  [m]
  (batching-file (:level m)
                 (io/file (:out m))))

(defn batching-console ;; not the greatest viewing experience with many threads writing
  [level]
  "A general-purpose console appender based on queueing/batching."
  (batching *out* (appenders/std-out level)))

(defn map->BatchingConsole
  [m]
  (batching-console (:level m)))
;; ========================================

(defrecord OnAgent [level agent logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ event]
    (send-off
      agent
      (fn [state]
        (proto/xlog logger event)
        (update state :events-written inc')))))

(defn on-agent
  "Returns a Logger which delegates to the provided one
   asynchronously on a separate thread via <agent>."
  ([agent logger]
   (on-agent (proto/getLevel logger) agent logger))
  ([level agent logger]
   (levels/ensure-valid-level! level)
   (OnAgent. level agent logger)))

(defn map->OnAgent
  [m]
  (on-agent (:level m)
            (agent {:events-written 0})
            (:logger m)))


(defn async-file
  ([fp]
   (async-file nil fp))
  ([level ^String fpath]
   (map->OnAgent {:level level
                  :logger (appenders/with-open-writer level fpath)})))

(defn map->AsyncFile
  [m]
  (async-file (:level m)
              (io/file (:out m))))

(defn async-console
  [level]
  (map->OnAgent {:level level
                 :logger (appenders/std-out level)}))

(defn map->AsyncConsole
  [m]
  (async-console (:level m)))
;============================================================

(defrecord FutureExecuting [level logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ event]
    (future (proto/xlog logger event))
    nil))

(defrecord PoolExecuting [level ^ExecutorService pool logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ event]
    (.submit pool ^Runnable
             (partial proto/xlog logger event))
    nil))

(defmethod print-method PoolExecuting [this ^Writer w]
  (.write w "#flog.loggers.PoolExecuting")
  (.write w (pr-str
              (merge (meta this)
                     (select-keys this [:level :logger])))))

(defn executing
  "Returns a Logger which delegates to the provided one
   on a separate thread. The 1-arg arity will use `future`,
   whereas the 2-arg one expects an  ExecutorService to submit to."
  ([logger]
   (executing (proto/getLevel logger) logger))
  ([level logger]
   (levels/ensure-valid-level! level)
   (FutureExecuting. level logger))
  ([level [type n :as pool] logger]
   (levels/ensure-valid-level! level)
   (with-meta
     (PoolExecuting. level (ut/thread-pool type n) logger)
     {:thread-pool pool})))

(defn map->Executing
  [m]
  (if-let [tp (:thread-pool m)]
    (executing (:level m)
               tp
               (:logger m))
    (executing (:level m)
               (:logger m))))

(defn filtering-levels
  "Returns a Logger which delegates to the provided one,
   only when the event's level is one of <levels>.
   Must be the first logger called (last in the composition)."
  ([levels logger]
   (filtering-levels (proto/getLevel logger) levels logger))
  ([level levels logger]
   (assert (set? levels))
   (filtering level (comp levels :level) logger)))

(defn map->FilteringLevels
  [m]
  (filtering-levels (:level m)
                    (set (:levels m))
                    (:logger m)))

(defn removing-levels
  "Returns a Logger which delegates to the provided one,
   only when the event's level is NOT one of <levels>.
   Must be the first logger called (last in the composition)."
  ([levels logger]
   (removing-levels (proto/getLevel logger) levels logger))
  ([level levels logger]
   (assert (set? levels))
   (removing level (comp levels :level) logger)))

(defn map->RemovingLevels
  [m]
  (removing-levels (:level m)
                   (set (:levels m))
                   (:logger m)))

(defn formatting-event
  "Returns a logger which delegates to the provided one,
   after having overwritten the event's `:msg` with the
   result from calling `(apply format fmt-pattern (f event))`."
  ([fmt-pattern f logger]
   (formatting-event (proto/getLevel logger) fmt-pattern f logger))
  ([level fmt-pattern f logger]
   (mapping
     level
     (fn [e]
       (assoc e :formatted (apply format fmt-pattern (f e))))
     logger)))

(defn map->FormattingEvent
  [m]
  (formatting-event (:level m)
                    (:pattern m)
                    (apply juxt (:juxt-keys m))
                    (:logger m)))

(defn cl-formatting-event
  "Returns a logger which delegates to the provided one,
   after having overwritten the event's `:msg` with the
   result from calling `(apply cl-format cl-fmt-pattern (f event))`."
  ([cl-fmt-pattern f logger]
   (cl-formatting-event (proto/getLevel logger) cl-fmt-pattern f logger))
  ([level cl-fmt-pattern f logger]
   (mapping
     level
     (fn [e]
       (assoc e :cl-formatted (apply cl-format nil cl-fmt-pattern (f e))))
     logger)))

(defn map->CLformattingEvent
  [m]
  (formatting-event (:level m)
                    (:pattern m)
                    (apply juxt (:juxt-keys m))
                    (:logger m)))

(comment

  (let [fmt "%-30s %-25s %-25s %-20s [%-5s] %s"
        ks [:issued-at :host :thread :origin :level :msg]
        error-logger (->> @(locking-console) ;; looks in-reverse like `comp`
                        (mapping :formatted)
                        (formatting-event fmt ks)
                        (filtering-levels #{"ERROR"}))
        info-logger (flog.core/chaining
                      @(locking-console)
                      (partial filtering-levels #{"INFO"})
                      (partial formatting-event fmt ks)
                      (partial mapping :formatted))
        ]

    (flog.core/info error-logger "batch-processor" "Hello world2"))

  )

(defn- nbytes
  [n unit]
  (case unit
    :KB (* n 1024)
    :MB (nbytes (* n 1000) :KB)
    :GB (nbytes (* n 1000) :MB)
    :TB (nbytes (* n 1000) :GB)
    n))

(defrecord RollingFileSize
  [level ^File file suffix limit ^AtomicLong counter logger]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (if (>= (.length file) limit)
      (let [roll-file (io/file (str file suffix (.incrementAndGet counter)))]
        (ut/move-file! file roll-file)
        (proto/xlog logger e))
      (proto/xlog logger e))))

(defmethod print-method RollingFileSize [this ^Writer w]
  (.write w "#flog.loggers.RollingFileSize")
  (.write w (pr-str (-> this
                        (dissoc :counter)
                        (update :file str)))))

(defn rolling-file-size
  ([f max-size logger]
   (rolling-file-size (proto/getLevel logger) f max-size logger))
  ([level f [limit unit] logger]
   (levels/ensure-valid-level! level)
   (let [counter (AtomicLong. 0)
         suffix ".backup"]
     (RollingFileSize. level f suffix (nbytes limit unit) counter logger))))

(defn map->RollingFileSize
  [m]
  (rolling-file-size (:level m)
                     (io/file (:file m))
                     [(:limit m)
                      (:unit m)]
                     (:logger m)))

(defn- backup-with-date
  [f]
  (let [date (LocalDate/now)
        suffix (str \. date)
        roll-file (str f suffix)]
    (ut/move-file! f roll-file)))

(defrecord RollingFileInterval
  [level file interval logger]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (proto/xlog logger e)))

(defmethod print-method RollingFileInterval [this ^Writer w]
  (.write w "#flog.loggers.RollingFileInterval")
  (.write w (pr-str (update this :file str))))

(defn rolling-file-interval
  ([f policy logger]
   (rolling-file-interval (proto/getLevel logger) f policy logger))
  ([level f policy logger]
   (levels/ensure-valid-level! level)
   (let [date (LocalDate/now)
         suffix (str \. date)
         now (LocalTime/now)
         [ms-until-roll interval ^TimeUnit unit]
         (case policy
           :daily  [(.until now (.atTime date 23 59 59) ChronoUnit/MILLIS)
                    24 TimeUnit/HOURS]
           :hourly [(.until now (.plus now 1 ChronoUnit/HOURS) ChronoUnit/MILLIS)
                    1 TimeUnit/HOURS]
           )]
     (future
       (Thread/sleep ms-until-roll)
       (let [roll-file (io/file (str f suffix))]
         (ut/move-file! f roll-file)
         (.scheduleAtFixedRate ;; dedicated thread for backing up the file
           (Executors/newSingleThreadScheduledExecutor)
           (partial backup-with-date f)
           0
           interval
           unit)))
     (RollingFileInterval. level (str f) policy logger))))

(defn map->RollingFileInterval
  [m]
  (rolling-file-interval (:level m)
                         (io/file (:file m))
                         (:interval m)
                         (:logger m)))

(def edn-readers
  {'flog.appenders.StdOut            appenders/map->StdOut
   'logger/StdOut                    appenders/map->StdOut

   'flog.appenders.PassThroughWriter appenders/map->PassThroughWriter
   'logger/PassThroughWriter         appenders/map->PassThroughWriter

   'flog.appenders.WithOpenWriter    appenders/map->WithOpenWriter
   'logger/WithOpenWriter            appenders/map->WithOpenWriter

   'flog.appenders.AtomOut           appenders/map->AtomOut
   'logger/AtomOut                   appenders/map->AtomOut

   'flog.loggers.Branching           map->Branching
   'logger/Branching                 map->Branching

   'flog.loggers.Locking             map->Locking
   'logger/Locking                   map->Locking

   'flog.loggers.Batching            map->Batching
   'logger/Batching                  map->Batching

   'logger/BatchingFile              map->BatchingFile
   'logger/BatchingConsole           map->BatchingConsole
   'logger/Executing                 map->Executing
   'logger/LockingConsole            map->LockingConsole
   'logger/AsyncConsole              map->AsyncConsole
   'logger/LockingFile               map->LockingFile


   'logger/Mapping                   map->Mapping
   'logger/Filtering                 map->Filtering
   'logger/Removing                  map->Removing
   'logger/FilteringLevels           map->FilteringLevels
   'logger/RemovingLevels            map->RemovingLevels
   'logger/FormattingEvent           map->FormattingEvent
   'logger/CLformattingEvent         map->CLformattingEvent

   'flog.loggers.RollingFileInterval map->RollingFileInterval
   'logger/RollingFileInterval       map->RollingFileInterval
   'flog.loggers.RollingFileSize     map->RollingFileSize
   'logger/RollingFileSize           map->RollingFileSize

   'logger/xlogger                   map->XLogger
   }

  )

