(ns flog.log-levels)

(def ^:const INFO  "INFO")
(def ^:const DEBUG "DEBUG")
(def ^:const TRACE "TRACE")
(def ^:const WARN  "WARN")
(def ^:const ERROR "ERROR")
(def ^:const REPORT "REPORT")
(def ^:const FATAL "FATAL")

(defonce priority
  (zipmap [REPORT TRACE  DEBUG  INFO  WARN  ERROR FATAL]
          (range 1 8)))

(defn ensure-valid-level!
  [level]
  (or (nil? level)
      (when-not (contains? priority level)
        (throw
          (IllegalArgumentException.
            (format "Invalid level <%s>!" level))))))

(defn enabled-for-ns?
  "Rule per Logback's basic selection rule:
   A log request of level p issued to a logger having
   an effective level q, is enabled if p >= q."
  [level ns-str config]
  (let [min-level (if-let [ns-levels (:nss config)]
                    (get ns-levels ns-str)
                    (:level config TRACE))]
    (>= (priority level)
        (priority min-level))))

