(ns flog.loggers
  (:require [flog
             [internal :as proto]
             [log-levels :as levels]
             [appenders :as appenders]
             [util :as ut]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [cl-format]]
            [circuit-breaker-fn
             [core :as cb]
             [util :as cb-ut]])
  (:import [flog.internal ILogger]
           (java.util.concurrent ConcurrentLinkedQueue ExecutorService TimeUnit Executors)
           (java.util.concurrent.locks ReentrantLock Lock)
           (java.io Writer File Closeable)
           (java.util.concurrent.atomic AtomicLong)
           (java.time LocalDate LocalTime ZonedDateTime)
           (java.time.temporal ChronoUnit IsoFields)
           (clojure.lang IFn)
           (java.time.format DateTimeFormatter)))


(defrecord XLogger [level f logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ e]
    (f logger e))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (.close ^Closeable logger)))

(defmethod print-method XLogger [this ^Writer w]
  (.write w "#flog.loggers.XLogger")
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

(defrecord Partitioning [level n state logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ e] ;; can't use the built-in stateful `partition-all` transducer
    (let [current (swap! state
                         (fn chunking* [items x]
                           (let [ret (conj items x)]
                             (if (> (count ret) n)
                               (conj (empty items) x)
                               ret)))
                         e)]
      (when (>= (count current) n)
        ;; partition of n full - proceed with logging
        ;; logger downstream must be able to handle multiple events
        (proto/xlog logger current))))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (when-let [remaining (not-empty @state)]
      ;; don't forget to flush!
      (proto/xlog logger remaining)
      (swap! state empty))
    (.close ^Closeable logger)))

(defmethod print-method Partitioning [this ^Writer w]
  (.write w "#flog.loggers.Partitioning")
  (.write w (pr-str (select-keys this [:level :n :logger]))))

(defn partitioning
  ([n logger]
   (partitioning (proto/getLevel logger) n logger))
  ([level n logger]
   (levels/ensure-valid-level! level)
   (Partitioning. level n (atom []) logger)))

(defn map->Partitioning
  [level n logger]
  (partitioning level n logger))

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
  (log [_ e]
    (run!
      #(when (enabled-for-logger? % level e)
         (proto/xlog % e))
      loggers))

  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (run! #(.close ^Closeable %) loggers)))

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
    (cb-ut/with-lock lock (proto/xlog logger e)))
  Closeable
  (close [_]
    (.close ^Closeable logger)))

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
   (with-locking level (appenders/pass-through-writer level f))))

(defn map->LockingFile
  [m]
  (locking-file (:level m) (io/file (:out m))))

;; BATCHING VARIANTS
;; =================
(defrecord Batching [level ^Lock lock ^ConcurrentLinkedQueue pending logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ event]
    (.offer pending event) ;; always true
    (cb-ut/with-try-lock lock
      ;; keep writing from this thread
      ;; for as long as there are elements
      (ut/while-let [e (.poll pending)]
        (proto/xlog logger e))))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (.close ^Closeable logger)))

(defn batching
  "Returns a logger backed by a ConcurrentLinkedQueue.
   Messages are queued and written out in bursts via <logger>,
   which MUST implement the 3-arg arity of ILogger.
   <out> per `io/writer`."
  ([logger]
   (batching (proto/getLevel logger)  logger))
  ([level logger]
   (levels/ensure-valid-level! level)
   (let [lock    (ReentrantLock.)
         pending (ConcurrentLinkedQueue.)]
     (Batching. level lock pending logger))))

(defmethod print-method Batching [this ^Writer w]
  (.write w "#flog.loggers.Batching")
  (.write w (pr-str (dissoc this :pending :lock))))

(defn map->Batching
  [m]
  (batching (:level m)
            (:logger m)))


(defn batching-file
  "High-performance & thread-safe file-logger
   which queues the producers & batches the consumers,
   in order to minimise threads in case of high-load.
   Not a good option for use-cases with high write latency
   (e.g. network resource)."
  ([f]
   (batching-file nil f))
  ([level ^File f]
   (batching level (appenders/pass-through-writer level f))))

(defn map->BatchingFile
  [m]
  (batching-file (:level m) (io/file (:out m))))

(defn batching-console ;; not the greatest viewing experience with many threads writing
  [level]
  "A general-purpose console appender based on queueing/batching."
  (batching (appenders/std-out level)))

(defn map->BatchingConsole
  [m]
  (batching-console (:level m)))
;; ========================================

(defrecord OnAgent [level agent logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ event]
    (let [[agent send-fn] agent]
      (send-off agent send-fn event)))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (.close ^Closeable logger)))

(defmethod print-method OnAgent [this ^Writer w]
  (let [agent-vec (:agent this)
        cb? (some-> agent-vec  first meta :circuit-breaker-fn.core/cb?)]
    (.write w (if (true? cb?)
                "#flog.loggers.OnCBAgent"
                "#flog.loggers.OnAgent"))
    (.write w (pr-str (-> this
                          (dissoc :agent)
                          (merge (meta agent-vec)))))))

(defn- agent-sender
  [logger state event]
  (proto/xlog logger event)
  (when (map? state)
    (update state :events-written inc')))

(defn on-agent
  "Returns a Logger which delegates to the provided one
   asynchronously on a separate thread via <agent>.
   A good option for high-latency use-cases."
  ([agent logger]
   (on-agent (proto/getLevel logger) agent logger))
  ([level agent logger]
   (levels/ensure-valid-level! level)
   (OnAgent. level
             [agent (partial agent-sender logger)]
             logger)))

(defn map->OnAgent
  [m]
  (on-agent (:level m)
            (agent {:events-written 0})
            (:logger m)))


(defn on-cb-agent
  "Returns a Logger which delegates to the provided one
   asynchronously on a separate thread via <util/agent-cb>.
   A good option for high-latency use-cases."
  ([cb-agent logger]
   (on-cb-agent (proto/getLevel logger) cb-agent logger))
  ([level [cb-agent wrap _ :as cb-vec] logger]
   (levels/ensure-valid-level! level)
   (OnAgent. level
             (update cb-vec 1 #(% (partial agent-sender logger)))
             logger)))

(defn map->OnCBAgent
  [m]
  (let [cb-ks [:fail-limit
               :fail-window
               :fail-window-unit
               :success-limit
               :open-timeout
               :timeout-unit
               :success-block
               :drop-fn
               :ex-fn]
        cb-params* (select-keys m cb-ks)
        cb-params (cond-> cb-params*
                          (:ex-fn cb-params*)
                          (update :ex-fn   ut/symbol->obj)

                          (:drop-fn cb-params*)
                          (update :drop-fn ut/symbol->obj))]
    (on-cb-agent (:level m)
                 (cb/cb-agent* {:events-written 0}
                               (assoc cb-params :meta cb-params*))
                 (:logger m))))

(defn async-file
  ([fp]
   (async-file nil fp))
  ([level ^File f]
   (map->OnAgent {:level level
                  :logger (appenders/pass-through-writer level f)})))

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
    nil)
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (.close ^Closeable logger)))

(defrecord PoolExecuting [level ^ExecutorService pool logger]
  ILogger
  (getLevel [_]
    (or (proto/getLevel logger) level)) ;; let levels bubble up
  (log [_ event]
    (.submit pool ^Runnable
             (partial proto/xlog logger event))
    nil)
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (.shutdown pool)
    (.close ^Closeable logger)))

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
      (let [fpath (.getPath file)
            roll-file (str fpath suffix (.incrementAndGet counter))]
        (ut/move-file! fpath roll-file)
        (proto/xlog logger e))
      (proto/xlog logger e)))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (.close ^Closeable logger)))

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

(defrecord RollingFileInterval
  [level interval-fn previous-call logger]
  ILogger
  (getLevel [_] (or (proto/getLevel logger) level))
  (log [_ e]
    (let [event-timestamp (-> e :timestamp :local)
          snap @previous-call ;; nil the very first time
          last-time (or snap (reset! previous-call event-timestamp))
          last-time-dt (ZonedDateTime/parse last-time)]
      (when snap ;; don't bother the first time
        (interval-fn last-time-dt (ZonedDateTime/parse event-timestamp))
        (reset! last-time event-timestamp))
      (proto/xlog logger e) ;; good to log now
      nil))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (.close ^Closeable logger)))

(defmethod print-method RollingFileInterval [this ^Writer w]
  (.write w "#flog.loggers.RollingFileInterval")
  (.write w (pr-str
              (merge (dissoc this :interval-fn :previous-call :logger)
                     (meta this)))))

(defn rolling-file-interval
  [level file policy]
   (levels/ensure-valid-level! level)
   (let [interval-fn
         (case policy
           :daily (fn [last-time-dt now-time-dt]
                    (let [previous-day (.getDayOfMonth last-time-dt)
                          current-day (.getDayOfMonth now-time-dt)]
                      (when (> current-day previous-day)
                        (let [date (.format now-time-dt DateTimeFormatter/ISO_LOCAL_DATE)
                              suffix (str \. date)
                              roll-file (str file suffix)]
                          (ut/move-file! file roll-file)))))
           :weekly (fn [last-time-dt now-time-dt]
                     (let [previous-week (.get last-time-dt IsoFields/WEEK_OF_WEEK_BASED_YEAR)
                           current-week (.get now-time-dt IsoFields/WEEK_OF_WEEK_BASED_YEAR)]
                       (when (> current-week previous-week)
                         (let [date (.format now-time-dt DateTimeFormatter/ISO_WEEK_DATE) ;; 2012-W48-6
                               suffix (str \. date)
                               roll-file (str file suffix)]
                           (ut/move-file! file roll-file)))))
           :hourly (fn [last-time-dt now-time-dt]
                     (let [previous-hour (.getHour last-time-dt)
                           current-hour (.getHour now-time-dt)]
                       (when (> current-hour previous-hour)
                         (let [date (.format now-time-dt DateTimeFormatter/ISO_LOCAL_DATE_TIME)
                               suffix (str \. date)
                               roll-file (str file suffix)]
                           (ut/move-file! file roll-file))))))]
     (with-meta
       (RollingFileInterval. level
                             interval-fn
                             (atom nil)
                             (appenders/pass-through-writer level file))
       {:file file
        :interval policy})))

(defn map->RollingFileInterval
  [m]
  (rolling-file-interval (:level m)
                         (:file m)
                         (:interval m)))



