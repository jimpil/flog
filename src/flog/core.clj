(ns flog.core
  (:require [flog
             [internal :as proto]
             [log-levels :as levels]]
            [clojure
             [string :as str]]))

(def ^:dynamic *root* nil)


(defmacro with-root-logger
  [logger & body]
  `(binding [*root* ~logger]
     ~@body))


(defn- fline [form]
  (:line (meta form)))

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
                              (get levels/priority (proto/getLevel logger)))]
     (>= level-priority ns-or-root-level))))

(defonce space-join   (partial str/join \space))
(defonce apply-format (partial apply format))

(defmacro log!
  "Base macro - internal impl subject to change!"
  [level msg-type args]
  (let [ns-str (str *ns*)
        args   (vec args)
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
           (proto/do-log! logger#
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
             (proto/do-log! ~LOGGER
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
