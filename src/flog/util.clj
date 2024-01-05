(ns flog.util
  (:import (org.apache.logging.log4j Level LogManager)
           (org.apache.logging.log4j.core.config Configurator)))

(defn name++
  "Like `clojure.core/name`, but takes into account the namespace."
  ^String [x]
  (if (string? x)
    x
    (if-some [ns-x (namespace x)]
      (str ns-x \/ (name x))
      (name x))))

(defn pr-map-fully
  ^String [m]
  (binding [*print-length* nil
            *print-level*  nil
            *print-meta*   nil]
    (pr-str m)))

(defn set-level!
  "Sets the specified <level> (keyword) on the <logger> (String).
   The 1-arg arity uses the :root logger (per `LogManager/ROOT_LOGGER_NAME`),
   which happens to be the empty string. For all levels use `:all`."
  ([level]
   (set-level! :root level))
  ([logger level]
   (let [level (Level/valueOf (name level))]
     (cond
       (= :root logger)
       (Configurator/setAllLevels LogManager/ROOT_LOGGER_NAME level)

       (string? logger)
       (Configurator/setLevel ^String logger level)

       (or (sequential? logger)
           (set? logger))
       (Configurator/setLevel (zipmap logger (repeat level)))

       :else
       (throw
         (IllegalArgumentException.
           (str "Unsupported logger type: " (type logger))))))))

(defn set-levels!
  "Similar to `set-level!`, but expects a map of logger-name => level"
  [loggers]
  {:pre [(map? loggers)]}
  (Configurator/setLevel (update-vals loggers #(Level/valueOf (name %)))))
