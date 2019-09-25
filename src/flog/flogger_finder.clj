(ns flog.flogger-finder
  (:gen-class :name    flog.integration.FloggerFinder
              :extends java.lang.System$LoggerFinder
              ;:constructors {[] []}
              ))

(let [slm (delay
            (-> 'flog.integration/system-logger-memo
                requiring-resolve
                var-get))]
  (defn -getLogger
    "Returns a subclass of `System$Logger` which
     routes logging requests to the `core/*root*` logger."
    [this name module]
    ;; resolve it at runtime (and only once),
    ;; in order to prevent AOT leaking out of this ns
    (@slm)))
