(ns flog.builder
  (:require [flog.context :as ctx])
  (:import (org.apache.logging.log4j LogBuilder Level)
           (org.apache.logging.log4j.core Logger LoggerContext)))

(defn at-level
  ^LogBuilder [^Logger logger ^Level level]
  (.atLevel logger level))

(defn with-throwable
  ^LogBuilder [^LogBuilder builder ^Throwable throwable]
  (.withThrowable builder throwable))

(defn context-logger
  ^Logger [^LoggerContext context ^String logger-ns]
  (.getLogger context logger-ns))

(defn ns-logger* [ns]
  (-> (ctx/manager-context)
      (context-logger (str ns))))

(defmacro location-info? []
  `(or
     (try
       (some-> (System/getProperty "flog.builder/include-location-info?")
               (Boolean/parseBoolean))
       (catch Exception _#))
     false))

(defmacro log-builder
  ([level]
   `(log-builder ~level ~(location-info?)))
  ([level location?]
   (if (true? location?)
     `(-> (ns-logger* ~*ns*)
          (at-level ~level)
          (.withLocation))
     `(-> (ns-logger* ~*ns*)
          (at-level ~level)))))

(defmacro fatal [] `(log-builder Level/FATAL))
(defmacro error [] `(log-builder Level/ERROR))
(defmacro warn  [] `(log-builder Level/WARN))
(defmacro info  [] `(log-builder Level/INFO))
(defmacro debug [] `(log-builder Level/DEBUG))
(defmacro trace [] `(log-builder Level/TRACE))

