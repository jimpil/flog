(ns flog.loggers-test
  (:require [clojure.test :refer :all]
            [flog
             [init :as init]
             [core :as core]
             [loggers :as loggers]]
            [clojure.string :as str]
            [flog.context :as context])
  (:import (java.io StringReader)))

(defn- info [x]
  (core/info x))

(defn- warn [x]
  (core/warn x))

(defn- debug [x]
  (core/debug x))

(defn- trace [x]
  (core/trace x))

(defn- error [x]
  (core/error x))

(defn- rand-level []
  (rand-nth [info
             warn
             debug
             error
             trace]))

(defn- do-console-variant
  [[origins messages]]
  (with-out-str
    (doall
      (pmap
        (fn [origin msg]
          (let [f (rand-level)]
            (context/with-logging-context
              {:origin origin}
              (f msg))))
        origins
        messages))))

(defn- dummy-msg
  [service]
  (str "LOG-MESSAGE FROM " service))

(deftest console-logging-tests
  (let [loggers-str "{:locking #logger/Executing{:logger  #logger/LockingConsole{:level \"DEBUG\"}} :async #logger/AsyncConsole{:level \"INFO\"}}"
        loggers (init/read-config (StringReader. loggers-str))
        n 50
        origins (repeatedly n #(rand-nth ["service1" "service2" "service3"]))
        messages (map dummy-msg origins)]

    (testing "locking-console + mapping"
      (let [actual (core/with-root-logger
                     (loggers/mapping :msg (:locking loggers))
                     (do-console-variant [origins messages]))]
        (is (every?
              #(.startsWith % "LOG-MESSAGE FROM")
              (str/split-lines actual)))))

      (testing "locking-console + removing-levels"
        (let [actual (core/with-root-logger
                       (loggers/removing-levels #{"INFO"} (:locking loggers))
                       (do-console-variant [origins messages]))]
          (is (>= n (count (str/split-lines actual))))))


    (testing "async-console + mapping"
      (let [actual (core/with-root-logger
                     (loggers/mapping :msg (:async loggers))
                     (do-console-variant [origins messages]))]
        (is (every?
              #(.startsWith % "LOG-MESSAGE FROM")
              (str/split-lines actual)))))

    (testing "async-console + removing-levels"
      (let [actual (core/with-root-logger
                     (loggers/removing-levels #{"INFO"} (:async loggers))
                     (do-console-variant [origins messages]))]
        (is (>= n (count (str/split-lines actual))))))

    )
  )

