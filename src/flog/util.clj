(ns flog.util
  (:require [flog.context :as ctx])
  (:import (org.apache.logging.log4j Level LogManager)))

(defn name++
  "Like `clojure.core/name`, but takes into account the namespace."
  [x]
  (if (string? x)
    x
    (if-some [ns-x (namespace x)]
      (str ns-x \/ (name x))
      (name x))))

(defn set-level!
  "Sets the specified <level> (keyword) on the logger
   named <logger-name> (String). The 1-arg arity uses
   the root logger (per `LogManager/ROOT_LOGGER_NAME`),
   which happens to be the empty string. For all levels
   use `:all`."
  ([level]
   (set-level! LogManager/ROOT_LOGGER_NAME level))
  ([logger-name level]
   (let [ctx (ctx/manager-context)]
     (-> ctx
         (.getConfiguration)
         (.getLoggerConfig (str logger-name))
         (.setLevel (Level/valueOf (name level))))
     (.updateLoggers ctx))))
