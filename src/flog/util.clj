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
  "Sets the specified <level> (keyword) on the <logger> (String). The 1-arg arity uses
   the root logger (per `LogManager/ROOT_LOGGER_NAME`),
   which happens to be the empty string. For all levels
   use `:all`."
  ([level]
   (set-level! LogManager/ROOT_LOGGER_NAME level))
  ([logger level]
   (let [level (Level/valueOf (name level))]
     (cond
       (= :all logger)
       (Configurator/setAllLevels LogManager/ROOT_LOGGER_NAME level)

       (string? logger)
       (Configurator/setLevel ^String logger level)

       (or (sequential? logger)
           (set? logger))
       (Configurator/setLevel (zipmap logger (repeat level)))

       (map? logger)
       (Configurator/setLevel logger)

       :else
       (throw
         (IllegalArgumentException.
           (str "Unsupported logger type: " (type logger))))))))
