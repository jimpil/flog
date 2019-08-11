(ns flog.internal)

(defprotocol ILogger
  (getLevel [this])
  (log [this event]
       [this writer event])) ;; arity to accommodate `pass-through-writer`

(defn logger? [x]
  (cond->> x
           (var? x) var-get
           true (instance? ILogger)))

(defn xlog
  "Convenience wrapper around the 2-arg protocol fn."
  ([logger] logger)
  ([logger e]
   (log logger e)))
