(ns flog.context
  (:import (org.apache.logging.log4j ThreadContext CloseableThreadContext LogManager)
           (org.apache.logging.log4j.core LoggerContext)))

;; MDC/NDC lexical scoping
;;========================
(defmacro with-ndc
  "Executes <body> after pushing <vs>,
   in the current NDC stack. Pops them
   back out before exiting its scope."
  [vs & body]
  `(with-open [_# (CloseableThreadContext/pushAll ~vs)]
     ~@body))


(defmacro with-mdc
  "Executes <body> after merging Map <m>,
   with the current MDC map. Restores it
   back before exiting its scope."
  [m & body]
  `(with-open [_# (CloseableThreadContext/putAll ~m)]
     ~@body))

(defmacro with-context
  "Like `with-mdc` & `with-ndc` combined."
  [mdc ndc & body]
  (if (nil? mdc)   ;; NDC only (mdc = literal nil)
    `(with-ndc ~ndc ~@body)
    (if (nil? ndc) ;; MDC only (ndc = literal nil)
      `(with-mdc ~mdc ~@body)
      `(with-mdc ~mdc (with-ndc ~ndc ~@body)))))

;; There should be no need to manually use this for regular logging
;; only when when sending/submitting to thread pools
(defmacro inherit-fn
  "A way to force ThreadContext inheritance onto worker threads.
   Returns the function to send/submit. It can take 0 or 1
   argument (purely for agent send-ability)."
  [& body]
  `(let [kvs# (ThreadContext/getImmutableContext)
         vs#  (.asList (ThreadContext/getImmutableStack))]
     (bound-fn
       ([_#] ;; 1-arg arity for sending to agent
        (with-context kvs# vs# ~@body))
       ([] ;; 0-arg arity for submitting to ExecutorService
        (with-context kvs# vs# ~@body)))))

(defn ^LoggerContext manager-context
  ([]
   (manager-context false))
  ([current?]
   (LogManager/getContext current?)))

