(ns flog.integration
  (:require [clojure.tools.logging]
            [flog.core :as core]
            [flog.internal :as internal]
            [flog.util :as ut]))

(deftype ToolsLogger [root-logger logger-ns-str config]
  clojure.tools.logging.impl/Logger

  (enabled? [_ level]
    ;; No support for per-call config
    (core/enabled-for-ns? level logger-ns-str config))

  (write! [_ level throwable message]
    (->> {:level   level
          :msg     message
          :?ns-str logger-ns-str
          :?error  throwable}
         (merge (ut/env-info))
         (internal/xlog root-logger))))

(deftype LoggerFactory [get-logger-fn]
  clojure.tools.logging.impl/LoggerFactory
  (name [_] "Flog")
  (get-logger [_ logger-ns]
    (get-logger-fn logger-ns)))



(defn start-flogging!
  "Sets the root binding of `clojure.tools.logging/*logger-factory*`
  to use Flog."
  ([root]
   (start-flogging! root (meta root)))
  ([root config]
   (let [get-ns-logger (memoize
                         (fn [logger-ns]
                           (ToolsLogger. root (str logger-ns) config)))]
     ;; warm-up the cache
     (run! get-ns-logger (all-ns))

     (alter-var-root #'clojure.tools.logging/*logger-factory*
       (fn [_]
         (LoggerFactory. get-ns-logger))))))
