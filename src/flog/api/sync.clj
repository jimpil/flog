(ns flog.api.sync
  (:require [flog.builder :as builder]
            [flog.data :as data]))

(defmacro fatal
  ([thing]        `(data/log* ~thing (builder/fatal)))
  ([thing & more] `(data/log* ~thing (builder/fatal) [~@more])))

(defmacro error
  ([thing]        `(data/log* ~thing (builder/error) ))
  ([thing & more] `(data/log* ~thing (builder/error) [~@more])))

(defmacro warn
  ([thing]        `(data/log* ~thing (builder/warn)))
  ([thing & more] `(data/log* ~thing (builder/warn) [~@more])))

(defmacro info
  ([thing]        `(data/log* ~thing (builder/info)))
  ([thing & more] `(data/log* ~thing (builder/info) [~@more])))

(defmacro debug
  ([thing]        `(data/log* ~thing (builder/debug)))
  ([thing & more] `(data/log* ~thing (builder/debug) [~@more])))

(defmacro trace
  ([thing]        `(data/log* ~thing (builder/trace)))
  ([thing & more] `(data/log* ~thing (builder/trace) [~@more])))

