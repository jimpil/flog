(ns flog.api.async
  (:require [clojure.tools.logging :as ctl]
            [flog.builder :as builder]
            [flog.context :as ctx]
            [flog.data :as data])
  (:import (clojure.lang Agent)))

(def exec
  "The underlying executor to use (lazy-loaded). If the runtime supports
   virtual-threads (java 19+), returns a `newThreadPerTaskExecutor`).
   Otherwise, returns the (usual) `Agent/soloExecutor`."
  (delay
    (try
      (eval
        `(-> (Thread/ofVirtual)
             (.name "flog-" 0)
             .factory
             java.util.concurrent.Executors/newThreadPerTaskExecutor))
      (catch Throwable _
        ;; fallback to what we've always used (i.e. per `send-off`)
        Agent/soloExecutor))))

(defmacro log*
  "Entry point for asynchronous logging.
   Sends (per `send-off`) the synchronous call
   to `clojure.tools.logging/*logging-agent*`,
   making sure it doesn't lose the context."
  ([builder thing]
   `(do (->> (data/log* ~thing ~builder)
             (ctx/agent-inherit-fn)
             (send-via @exec ctl/*logging-agent*))
        nil))
  ([builder thing varargs]
   `(do (->> (data/log* ~thing ~builder ~varargs)
             (ctx/agent-inherit-fn)
             (send-via @exec ctl/*logging-agent*))
        nil)))

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
