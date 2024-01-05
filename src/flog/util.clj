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
  "Wrapper around `Configurator.setLevel()`. <logger> can be String/sequential/set.
   The 1-arg arity can be used to either pass a map (logger => level),
   or a normal level keyword, in which case the :root logger (per `LogManager/ROOT_LOGGER_NAME`),
   will be used. For `Level/ALL` levels use `:all`."
  ([loggers-or-level]
   (if (map? loggers-or-level)
     (Configurator/setLevel (update-vals loggers-or-level #(Level/valueOf (name %))))
     (set-level! LogManager/ROOT_LOGGER_NAME loggers-or-level)))
  ([logger level]
   (let [level (Level/valueOf (name level))]
     (cond
       (string? logger)
       (Configurator/setLevel ^String logger level)

       (or (sequential? logger)
           (set? logger))
       (Configurator/setLevel (zipmap logger (repeat level)))

       :else
       (throw
         (IllegalArgumentException.
           (str "Unsupported <logger> type: " (type logger))))))))

(defn set-all-levels!
  "Wrapper around `Configurator.setAllLevels()`. <parent-logger> should be a String
   - defaults to `LogManager/ROOT_LOGGER_NAME` in the 1-arg arity."
  ([level]
   (set-all-levels! LogManager/ROOT_LOGGER_NAME level))
  ([parent-logger level]
   (->> (Level/valueOf level)
        (Configurator/setAllLevels parent-logger))))
