(ns flog.init
  (:require [flog
             [core :as core]
             [internal :as proto]
             [log-levels :as levels]
             [loggers :as loggers]
             [readers :as readers]
             [util :as ut]]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (java.io Closeable PushbackReader)))

(defn read-config
  ([conf]
   (read-config conf nil))
  ([conf readers]
   (readers/with-readers readers
     (with-open [rdr (PushbackReader. (io/reader conf))]
       (edn/read {:readers readers/*readers*} rdr)))))

(defn init-with-root!
  [root]
  (alter-var-root core/*root* (constantly root))
  (core/tracef "Root logger initialised per profile:%s%s"
          (str \newline)
          (meta root))
  (core/debug "Flog away..."))

(defn profile->root
  [profile]
  (let [sync? (:sync? profile)]
    (cond-> (loggers/branching
              (:level profile levels/TRACE)
              (:loggers profile))
            (not sync?) loggers/executing
            true (with-meta profile))))

(let [p (promise)]
  (defn init-with-config!
    ""
    ([] ;; arity for unknown config - use `with-readers` if you have custom readers
     (if-let [profiles  (System/getProperty "flogging.profiles")]
       (if-let [profile (System/getProperty "flogging.profile")]
         (init-with-config! profiles profile nil)
         (throw
           (IllegalStateException. "`flogging.profile` system-property not set!")))
       (throw
         (IllegalStateException. "`flogging.profiles` system-property not set!"))))
    ([root-logger]
     (init-with-config! nil root-logger nil)) ;; arity for known logger
    ([profile extra-readers] ;; arity for known profile w/o custom readers
     (init-with-config! [nil profile] extra-readers))
    ([profiles profile* extra-readers]     ;; arity for known config w/ custom readers
     (let [logger? (proto/logger? profile*)
           root (if logger? ;; maybe this is useful?
                  (with-meta profile* {:level (proto/getLevel profile*)
                                       :nss (-> profile* meta :nss)})
                  (let [global-config (read-config profiles extra-readers)
                        file-watch (:watch? global-config)
                        profile (get global-config
                                     (cond-> profile* (string? profile*) keyword))]
                    (when (and file-watch
                               (not (realized? p)))
                      (ut/start-watching-file!
                        (fn [_]
                          (deliver p true) ;; marker so we never run this code again
                          (init-with-config! profiles profile* extra-readers))
                        (io/file profiles)))
                    (profile->root profile)))]
       (init-with-root! root)))))

(defn stop-flogging!
  "Flushes and closes the *root* logger (if there is one)."
  []
  (core/trace "Flushing leftovers - No more flogging for you!")
  (when (some? core/*root*)
    (.close ^Closeable core/*root*)))
