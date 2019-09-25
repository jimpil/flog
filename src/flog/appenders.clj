(ns flog.appenders
  (:require [flog.util :as ut]
            [clojure.java.io :as io]
            [flog.http.client :as http]
            [flog.log-levels :as levels]
            [flog.internal :as proto])
  (:import (flog.internal ILogger)
           (java.io Writer Closeable)
           (java.net URI)
           (clojure.lang IFn)))

(defonce ^String NEWLINE
  (System/getProperty "line.separator"))

;; APPENDERS ARE THEMSELVES LOGGERS.
;; THE DIFFERENCE IS THAT APPENDERS ACTUALLY WRITE.
;; ================================================

(defrecord PassThroughWriter [level ^Writer wrt]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (ut/wr-str wrt e)
    (.write wrt NEWLINE))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (.flush wrt)
    (.close wrt)))

(defmethod print-method PassThroughWriter [this ^Writer w]
  (.write w "#flog.appenders.PassThroughWriter")
  (.write w (pr-str (-> this
                        (dissoc :wrt)
                        (merge (meta this))))))

(defn pass-through-writer
  "Logger which leaves the writer open."
  [level out]
  (levels/ensure-valid-level! level)
  (with-meta
    (PassThroughWriter. level (io/writer out :append true))
    {:out out}))

(defn map->PassThroughWriter
  [m]
  (pass-through-writer (:level m) (:out m)))

(defrecord StdOut [level]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (if (string? e)
      (println e)
      (prn e)))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_] (flush))) ;; don't close *out*!

(defmethod print-method StdOut [this ^Writer w]
  (.write w "#flog.appenders.StdOut")
  (.write w (pr-str (select-keys this [:level]))))

(defn std-out
  "Logger which writes events to *out*."
  [level]
  (levels/ensure-valid-level! level)
  (StdOut. level))

(defrecord StdErr [level]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (binding [*out* *err*]
      (if (string? e)
        (println e)
        (prn e))))
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (binding [*out* *err*]
      (flush)))) ;; don't close *out*!

(defmethod print-method StdErr [this ^Writer w]
  (.write w "#flog.appenders.StdErr")
  (.write w (pr-str (select-keys this [:level]))))

(defn std-err
  "Logger which writes events to *err*."
  [level]
  (levels/ensure-valid-level! level)
  (StdErr. level))


(defrecord AtomOut [level out]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (swap! out conj e)
    nil)
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    (swap! out empty)
    nil))

(defmethod print-method AtomOut [this ^Writer w]
  (.write w "#flog.appenders.AtomOut")
  (.write w (pr-str (select-keys this [:level]))))

(defn in-atom
  "Logger which pours all events into atom <a> (via `conj`).
   Very useful for testing."
  [level a]
  (levels/ensure-valid-level! level)
  (AtomOut. level a))

(defn map->AtomOut
  [m]
  (in-atom (:level m) (atom [])))

(defrecord HttpPost [level request-builder client to-string]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (http/post! @client request-builder (to-string e)) ;; timeout in seconds
    nil)
  IFn
  (invoke [this x]
    (proto/xlog this x))
  Closeable
  (close [_]
    nil))

(defn http-post
  [level url timeout {:keys [thread-pool
                             redirect-policy ;; :always, :never, :normal
                             version   ;; :http2 or :http1.1
                             priority
                             connect-timeout
                             proxy  ;;{:keys [host port] :as proxy}
                             credentials
                             json-str] ;; e.g. clojure.data.json/write-str
                      :as client-opts}]
  (levels/ensure-valid-level! level)
    (let [client  (delay
                    ;; the HttpClient has its own DEBUG logs!
                    ;; delay constructing it until absolutely necessary
                    (http/standard-client (update client-opts :credentials ut/symbol->obj)))
          builder (http/build-req (URI/create url) (boolean json-str) timeout)]
      (with-meta
        (HttpPost. level builder client (or (some-> json-str ut/symbol->obj)
                                            pr-str))
        {:client client-opts
         :url url
         :timeout timeout})))

(defn map->HttpPost
  [m]
  (let [client-opts (:client m)]
    (http-post (:level m)
               (:url m)
               (:timeout m)
               client-opts)))

(defmethod print-method HttpPost [this ^Writer w]
  (.write w "#flog.appenders.HttpPost")
  (.write w (pr-str (-> this
                        (dissoc :request-builder :to-string)
                        (merge (meta this))))))
