(ns flog.api.sync
  (:require [flog.builder :as builder]
            [flog.data :as data])
  (:import (org.apache.logging.log4j LogBuilder)))

(defn- error-keys
  [throwable]
  {:error/message (ex-message throwable)
   :error/data    (ex-data throwable)
   :error/cause   (ex-cause throwable)
   :error/class   (class throwable)})

(defn log*
  "Entry point for synchronous logging."
  ([^LogBuilder builder thing]
   (if (instance? Throwable thing)
     (->> (builder/with-throwable builder thing)
          (data/log* (error-keys thing)))
     (data/log* thing builder)))
  ([^LogBuilder builder thing varargs]
   (if (instance? Throwable thing)
     (-> (builder/with-throwable builder thing)
         (log*
           (if (= 1 (count varargs))
             (first varargs)
             (apply hash-map varargs))))
     (data/log* thing builder varargs))))

(defmacro fatal
  ([thing]        `(log* (builder/fatal) ~thing))
  ([thing & more] `(log* (builder/fatal) ~thing [~@more])))

(defmacro error
  ([thing]        `(log* (builder/error) ~thing))
  ([thing & more] `(log* (builder/error) ~thing [~@more])))

(defmacro warn
  ([thing]        `(log* (builder/warn) ~thing))
  ([thing & more] `(log* (builder/warn) ~thing [~@more])))

(defmacro info
  ([thing]        `(log* (builder/info) ~thing))
  ([thing & more] `(log* (builder/info) ~thing [~@more])))

(defmacro debug
  ([thing]        `(log* (builder/debug) ~thing))
  ([thing & more] `(log* (builder/debug) ~thing [~@more])))

(defmacro trace
  ([thing]        `(log* (builder/trace) ~thing))
  ([thing & more] `(log* (builder/trace) ~thing [~@more])))

