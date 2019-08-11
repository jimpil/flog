(ns flog.core
  (:require [flog.internal :as internal]
            [flog.log-levels :as levels]
            [flog.loggers :as loggers]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [flog.util :as ut])
  (:import (java.io PushbackReader)))

(def ^:dynamic *root* nil)
(def ^:dynamic *logging-context* nil)

;;helper macros
(defmacro with-root-logger
  [logger & body]
  `(binding [flog.core/*root* ~logger]
     ~@body))


(defmacro with-logging-context
  "Merges the current value of *logging-context*
   with context <c>, and binds it back to *logging-context*."
  [c & body]
  `(binding [flog.core/*logging-context*
             (merge flog.core/*logging-context* ~c)]
     ~@body))
;==========================
(defn read-config
  [conf]
  (with-open [rdr (PushbackReader. (io/reader conf))]
    (edn/read {:readers loggers/edn-readers} rdr)))

(defn- fline [form]
  (:line (meta form)))

(defmacro level>=
  [event-level min-level]
  `(>= ~event-level ~min-level))

(def ^:private search-config
  (memoize
    (fn [nss ns-str]
      (some (fn [[ns-pattern ns-map]]
              (when (re-matches (re-pattern ns-pattern) ns-str)
                ns-map))
            nss))))

(defn find-ns-in-config
  [config ns-str]
  (let [nss (:nss config)]
    (or (some-> nss (get ns-str))
        (search-config nss ns-str))))


(defn emit-log-call?
  ([level ns-level]
   (emit-log-call? *root* level ns-level))
  ([logger level ns-level]
   (let [level-priority (-> level name levels/priority)
         ns-or-root-level (or (some-> ns-level levels/priority)
                              (get levels/priority (internal/getLevel logger)))]
     (level>= level-priority ns-or-root-level))))

(defonce space-join   (partial str/join \space))
(defonce apply-format (partial apply format))

(defn do-log!
  [logger ns-mdc level-name file line provided-context to-msg args]
  (->> {:msg    (to-msg args)
        :level  level-name
        :file   file
        :line   line
        ;:config (meta logger)
        }
       (merge (flog.util/env-info)  ;; generated anew each time
              ns-mdc                ;; provided statically
              *logging-context*     ;; provided dynamically
              provided-context)     ;; provided at the call-site
       (flog.internal/xlog logger))
  )

(defmacro log!
  "Base macro - internal impl subject to change!"
  [level msg-type args]
  (let [ns-str (str *ns*)
        args  (vec args)
        [farg sarg]  ((juxt first second) args)
        ?file  *file*
        ?line  (fline &form)
        ?file (when (not= "NO_SOURCE_PATH" ?file) ?file)
        to-msg (case msg-type
                 :p #'flog.core/space-join
                 :f #'flog.core/apply-format)
        LOGGER flog.core/*root*
        CONFIG (when LOGGER (meta LOGGER))
        NS-INFO (when CONFIG (find-ns-in-config CONFIG ns-str))]

    (if (nil? LOGGER) ;; emit code that does everything at runtime
      `(let [logger# flog.core/*root*
             level# ~(name level)
             pcontext# (when (map? ~farg) ~farg)
             config# (meta logger#)
             ns-info# (find-ns-in-config config# ~ns-str)
             ns-mdc# (some-> ns-info# :mdc)
             args# (cond->> ~args pcontext# rest)]
         (when (flog.core/emit-log-call? logger# ~level (:level ns-info#))
           (flog.core/do-log! logger#
                              ns-mdc#
                              level#
                              ~?file
                              ~?line
                              pcontext#
                              ~to-msg
                              args#)))
      ;; decide at compile-time whether to emit the log-call altogether
      (when (flog.core/emit-log-call? LOGGER level (:level NS-INFO))
        (let [level-name (name level)
              ns-mdc (some-> NS-INFO :mdc)]
          `(let [pcontext# (when (map? ~farg) ~farg) ;; provided-context may not be a compile-time constant
                 args# (cond->> ~args pcontext# rest)]
             (flog.core/do-log! ~LOGGER
                                ~ns-mdc
                                ~level-name
                                ~?file
                                ~?line
                                pcontext#
                                ~to-msg
                                args#))))
      )
    )
  )

;;; Log using print-style args
(defmacro log* [level & args]
  (with-meta ;; CLJ-865
    `(log! ~level :p ~args)
    (meta &form)))

(defmacro trace [& args]
  (with-meta ;; CLJ-865
    `(log! :TRACE :p ~args)
    (meta &form)))

(defmacro debug [& args]
  (with-meta ;; CLJ-865
    `(log! :DEBUG :p ~args)
    (meta &form)))

(defmacro info [& args]
  (with-meta ;; CLJ-865
    `(log! :INFO :p ~args)
    (meta &form)))

(defmacro warn [& args]
  (with-meta ;; CLJ-865
    `(log! :WARN :p ~args)
    (meta &form)))

(defmacro error [& args]
  (with-meta ;; CLJ-865
    `(log! :ERROR :p ~args)
    (meta &form)))

(defmacro fatal [& args]
  (with-meta ;; CLJ-865
    `(log! :FATAL :p ~args)
    (meta &form)))

(defmacro report [& args]
  (with-meta ;; CLJ-865
    `(log! :REPORT :p ~args)
    (meta &form)))

;;; Log using format-style args
(defmacro logf* [level & args]
  `(log! ~level  :f ~args))

(defmacro tracef [& args]
  (with-meta ;; CLJ-865
    `(log! :TRACE :f ~args)
    (meta &form)))

(defmacro debugf [& args]
  (with-meta ;; CLJ-865
    `(log! :DEBUG :f ~args)
    (meta &form)))

(defmacro infof [& args]
  (with-meta ;; CLJ-865
    `(log! :INFO :f ~args)
    (meta &form)))

(defmacro warnf [& args]
  (with-meta ;; CLJ-865
    `(log! :WARN :f ~args)
    (meta &form)))

(defmacro errorf [& args]
  (with-meta ;; CLJ-865
    `(log! :ERROR :f ~args)
    (meta &form)))

(defmacro fatalf [& args]
  (with-meta ;; CLJ-865
    `(log! :FATAL :f ~args)
    (meta &form)))

(defmacro reportf [& args]
  (with-meta ;; CLJ-865
    `(log! :REPORT :f ~args)
    (meta &form)))
;;======================================
(defn init-with-root!
  [root]
  (alter-var-root *root* (constantly root))
  (tracef "Root logger initialised per profile:%s%s"
          (str \newline)
          (meta root))
  (debug "Flog away..."))

(defn init-with-config!
  ""
  ([]
   (if-let [profiles  (System/getProperty "flogging.profiles")]
     (if-let [profile (System/getProperty "flogging.profile")]
       (-> profiles
           read-config
           (get (keyword profile))
           init-with-config!)
       (throw
         (IllegalStateException. "`flogging.profile` system-property not set!")))
     (throw
       (IllegalStateException. "`flogging.profiles` system-property not set!"))))
  ([profile]
   (let [root (-> (loggers/branching
                    (:level profile levels/TRACE)
                    (:loggers profile))
                  (with-meta profile))]
     (init-with-root! root))))

(defn enabled-for-ns?
  "Rule per Logback's basic selection rule:
   A log request of level p issued to a logger having
   an effective level q, is enabled if p >= q."
  [level ns-str config]
  (let [min-level (if-let [ns-levels (:nss config)]
                    (get ns-levels ns-str)
                    (:level config levels/TRACE))]
    (level>= (levels/priority level)
             (levels/priority min-level))))



