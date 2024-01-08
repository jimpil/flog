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

(defn ns-logger
  ^Logger [^String ns-name]
  (-> (ctx/manager-context)
      (context-logger ns-name)))

(defmacro location-info? []
  `(-> "flog.builder/include-location-info?"
       System/getProperty
       Boolean/parseBoolean))

(defmacro log-builder
  ([level]
   `(log-builder ~level ~(location-info?)))
  ([level location?]
   (if (true? location?)
     `(-> (ns-logger ~(str *ns*))
          (.atLevel ~level)
          (.withLocation))
     `(-> (ns-logger ~(str *ns*))
          (.atLevel ~level)))))

(defmacro fatal [] `(log-builder Level/FATAL))
(defmacro error [] `(log-builder Level/ERROR))
(defmacro warn  [] `(log-builder Level/WARN))
(defmacro info  [] `(log-builder Level/INFO))
(defmacro debug [] `(log-builder Level/DEBUG))
(defmacro trace [] `(log-builder Level/TRACE))

