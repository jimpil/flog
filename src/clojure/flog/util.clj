(ns flog.util
  (:require [flog.context :as ctx])
  (:import (org.apache.logging.log4j Level LogManager)
           (org.apache.logging.log4j.core.config Configuration Configurator)))

(defn name++
  "Like `clojure.core/name`, but takes into account the namespace."
  ^String [x]
  (if (string? x)
    x
    (if-some [ns-x (namespace x)]
      (str ns-x \/ (name x))
      (name x))))

(defn pr-map-fully
  ^String [m]
  (binding [*print-length* nil
            *print-level*  nil
            *print-meta*   nil]
    (pr-str m)))

(defn- set-level*
  [^Configuration cfg ^String logger ^Level level]
  (-> cfg
      (.getLoggerConfig logger)
      (.setLevel level)))

(defn set-level!
  "Sets the <level> of some <logger> in the current configuration (can be String/sequential/set).
   The 1-arg arity can be used to either pass a map (logger => level),
   or a normal level keyword, in which case the :root logger (per `LogManager/ROOT_LOGGER_NAME`),
   will be used. For `Level/ALL` levels use `:all`."
  ([loggers-or-level]
   (if (map? loggers-or-level)
     (let [ctx (ctx/manager-context)
           cfg (.getConfiguration ctx)]
       (run!
         (fn [[logger level]]
           (->> (name level)
                (Level/valueOf)
                (set-level* cfg logger)))
         loggers-or-level)
       (.updateLoggers ctx))
     (set-level! LogManager/ROOT_LOGGER_NAME loggers-or-level)))
  ([logger level]
   (let [ctx   (ctx/manager-context)
         cfg   (.getConfiguration ctx)
         level (Level/valueOf (name level))]
     (cond
       (string? logger)
       (set-level* cfg logger level)

       (or (sequential? logger)
           (set? logger))
       (run! #(set-level* cfg % level) logger)

       :else
       (throw
         (IllegalArgumentException.
           (str "Unsupported <logger> type: " (type logger)))))

     (.updateLoggers ctx))))

(defn set-all-levels!
  "Wrapper around `Configurator.setAllLevels()`. <parent-logger> should be a String
   - defaults to `LogManager/ROOT_LOGGER_NAME` in the 1-arg arity."
  ([level]
   (set-all-levels! LogManager/ROOT_LOGGER_NAME level))
  ([parent-logger level]
   (->> (Level/valueOf (name level))
        (Configurator/setAllLevels parent-logger))))
