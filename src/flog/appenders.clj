(ns flog.appenders
  (:require [flog.util :as ut]
            [clojure.java.io :as io]
            [flog.internal :as proto])
  (:import (flog.internal ILogger)
           (java.io Writer Closeable Flushable)))

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
  Closeable
  (close [_]
    (.flush wrt)
    (.close wrt)))

(defn pass-through-writer
  "Logger which leaves the writer open."
  [level out]
  (PassThroughWriter. level (io/writer out :append true)))

(defn map->PassThroughWriter
  [m]
  (pass-through-writer (:level m) (:out m)))

#_(defrecord WithOpenWriter [level out]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (with-open [wrt (io/writer out :append true)]
      (ut/wr-str wrt e)
      (.write wrt NEWLINE)
      (.flush wrt))))

#_(defn with-open-writer
  "Logger which closes the writer on each call."
  [level out]
  (WithOpenWriter. level out))

(defrecord StdOut [level]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (if (string? e)
      (println e)
      (prn e)))
  Closeable
  (close [_] (flush))) ;; don't close *out*!


(defn std-out
  "Logger which writes events to *out*."
  [level]
  (StdOut. level))

(defrecord AtomOut [level out]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (swap! out conj e)
    nil)
  Closeable
  (close [_] nil))

(defn in-atom
  "Logger which pours all events into atom <a> (via `conj`).
   Very useful for testing."
  [level a]
  (AtomOut. level a))

(defn map->AtomOut
  [m]
  (in-atom (:level m) (atom (:out m))))
