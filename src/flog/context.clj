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

(defn inherit-fn
  "Wraps <f> so that the current thread's context is inherited before calling it.
   The assumption is that <f> will run on a different thread, but will want access
   to the current context."
  [f]
  (let [ks (ThreadContext/getImmutableContext)
        vs (.asList (ThreadContext/getImmutableStack))]
    (fn ;; optimise up to 3 args
      ([]
       (with-context ks vs (f)))
      ([a]
       (with-context ks vs (f a)))
      ([a b]
       (with-context ks vs (f a b)))
      ([a b c]
       (with-context ks vs (f a b c)))
      ([a b c d & more]
       (with-context ks vs (apply f a b c d more))))
    )
  )

;; There should be no need to manually use this for regular logging
;; only when sending/submitting to thread pools
(defmacro agent-inherit-fn
  "A way to force ThreadContext inheritance onto worker threads
   within Clojure - i.e. Agents. Returns the function to send (1-arg)."
  [& body]
  `(inherit-fn (fn [_#] ~@body)))

(defmacro executor-inherit-fn
  "A way to force ThreadContext inheritance onto worker threads
   outside clojure - i.e. Executors. Returns the function to submit (no-args)."
  [& body]
  `(inherit-fn (bound-fn [] ~@body)))

(defn ^LoggerContext manager-context
  ([]
   (manager-context false))
  ([current?]
   (LogManager/getContext current?)))

