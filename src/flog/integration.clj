(ns flog.integration
  (:require [clojure.tools.logging]
            [flog.core :as core]
            [flog.internal :as proto]
            [flog.util :as ut]
            [flog.log-levels :as levels])
  (:import (java.lang System$Logger System$Logger$Level)))

(deftype ToolsLogger [root-logger logger-ns-str config]
  clojure.tools.logging.impl/Logger

  (enabled? [_ level]
    ;; No support for per-call config
    (levels/enabled-for-ns? level logger-ns-str config))

  (write! [_ level throwable message]
    (->> {:level   level
          :msg     message
          :?ns-str logger-ns-str
          :?error  throwable}
         (merge (ut/base-info))
         (proto/xlog root-logger))))

(deftype LoggerFactory [get-logger-fn]
  clojure.tools.logging.impl/LoggerFactory
  (name [_] "Flog")
  (get-logger [_ logger-ns]
    (get-logger-fn logger-ns)))



(defn tools-flogging!
  "Sets the root binding of `clojure.tools.logging/*logger-factory*`
  to use Flog."
  ([root]
   (tools-flogging! root (meta root)))
  ([root config]
   (let [get-ns-logger (memoize
                         (fn [logger-ns]
                           (ToolsLogger. root (str logger-ns) config)))]
     ;; warm-up the cache
     ;(run! get-ns-logger (all-ns))

     (alter-var-root #'clojure.tools.logging/*logger-factory*
       (fn [_]
         (LoggerFactory. get-ns-logger))))))
;;=========================================
(defmacro call-site
  []
  `(let [^StackTraceElement caller# (-> (Thread/currentThread)
                                        .getStackTrace
                                        (aget 5))] ;; it's pretty deep

     [(.getLineNumber caller#)
      (.getClassName  caller#)
      (.getMethodName caller#)]))

(defn- ^:static jlog*
  [xlogger ^System$Logger$Level level msg params]
  (let [[line class-name method] (call-site)]
    (if (instance? Throwable params)
      (proto/do-log! xlogger
                     nil
                     (.getName level)
                     class-name
                     line
                     nil
                     core/space-join
                     (cond-> [msg] (some? params) (conj params)))
      (proto/do-log! xlogger
                     nil
                     (.getName level)
                     class-name
                     line
                     nil
                     core/apply-format
                     (apply conj [msg] (seq params))))))

(defn system-logger
  "Returns an extension of `System$Logger` which delegates
   to the provided <xlogger>."
  ([]
   (system-logger core/*root*))
  ([xlogger]
   (proxy [System$Logger] []
     (getName []
       (pr-str xlogger))
     (isLoggable [elevel]
       (>= (levels/priority (.getName elevel))
           (levels/priority (proto/getLevel xlogger))))
     (log
       ([level msg]
        (jlog* xlogger level msg (int-array 0)))
       ([level msg params]
        (jlog* xlogger level msg params))
       ([level resource-bundle msg params]
        (jlog* xlogger level msg params))
       )
     )
   )
  )

(defonce system-logger-memo
  (memoize system-logger))
