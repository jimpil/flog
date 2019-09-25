(ns flog.internal
  (:require [flog
             [util :as ut]
             [context :as context]]))

(defprotocol ILogger
  (getLevel [this])
  (log [this event]))

(defn logger? [x]
  (cond->> x
           (var? x) var-get
           true (instance? ILogger)))

(defn xlog
  "Convenience wrapper around the 2-arg protocol fn."
  ([logger] logger)
  ([logger e]
   (log logger e)))

(defn do-log!
  [logger ns-mdc level-name file line provided-context to-msg args]
  (->> {:msg    (to-msg args)
        :level  level-name
        :file   file
        :line   line
        ;:config (meta logger)
        }
       (merge (ut/base-info)      ;; generated anew each time
              ns-mdc                    ;; provided statically
              context/*logging-context* ;; provided dynamically
              provided-context)         ;; provided at the call-site
       (xlog logger))
  )
