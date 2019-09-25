(ns flog.context)

(def ^:dynamic *logging-context* nil)

(defmacro with-logging-context
  "Merges the current value of *logging-context*
   with context <c>, and binds it back to *logging-context*,
   before executing <body>."
  [c & body]
  `(binding [*logging-context* (merge *logging-context* ~c)]
     ~@body))
