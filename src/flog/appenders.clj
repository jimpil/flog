(ns flog.appenders
  (:require [flog.util :as ut]
            [clojure.java.io :as io]
            [flog.internal :as proto])
  (:import (flog.internal ILogger)
           (java.io Writer)))

(defonce ^String NEWLINE
  (System/getProperty "line.separator"))

;; APPENDERS ARE THEMSELVES LOGGERS.
;; THE DIFFERENCE IS THAT APPENDERS ACTUALLY WRITE.
;; ================================================

(defrecord PassThroughWriter [level]
  ILogger
  (getLevel [_] level)
  (log [_ wrt e]
    (ut/wr-str wrt e)
    (.write ^Writer wrt NEWLINE)))

(defn pass-through-writer
  "Logger which leaves the writer open."
  [level]
  (PassThroughWriter. level))

(defrecord WithOpenWriter [level out]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (with-open [wrt (io/writer out :append true)]
      (ut/wr-str wrt e)
      (.write wrt NEWLINE)
      (.flush wrt))))

(defn with-open-writer
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
  (log [this _ e]
    (proto/log this e)))

(defn std-out
  "Logger which writes events to *out*."
  [level]
  (StdOut. level))

(defrecord AtomOut [level out]
  ILogger
  (getLevel [_] level)
  (log [_ e]
    (swap! out conj e)
    nil))

(defn in-atom
  "Logger which pours all events into atom <a> (via `conj`).
   Very useful for testing."
  [level a]
  (AtomOut. level a))

(defn map->AtomOut
  [m]
  (in-atom (:level m) (atom (:out m))))
