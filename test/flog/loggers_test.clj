(ns flog.loggers-test
  (:require [clojure.test :refer :all]
            [flog.core :as core]
            [clojure.string :as str]
            [flog.loggers :as loggers])
  (:import (java.io StringReader)))

(defn- rand-level []
  (rand-nth [core/info
             core/warn
             core/debug
             core/error
             core/trace]))

(defn- do-console-variant
  [logger [origins messages]]
  (with-out-str
    (doall
      (pmap
        (fn [origin msg]
          (let [f (rand-level)]
            (f logger origin msg)))
        origins
        messages))))

(defn- dummy-msg
  [service]
  (str "LOG-MESSAGE FROM " service))

(deftest console-logging-tests
  (let [loggers-str "{:locking #logger/Executing{:logger  #logger/LockingConsole{:level \"DEBUG\"}} :async #logger/AsyncConsole{:level \"INFO\"}}"
        loggers (core/read-config (StringReader. loggers-str))
        n 50
        origins (repeatedly n #(rand-nth ["service1" "service2" "service3"]))
        messages (map dummy-msg origins)]

    (testing "locking-console + mapping"
      (let [actual (do-console-variant
                     (loggers/mapping :msg (:locking loggers))
                     [origins messages])]
        (is (every?
              #(.startsWith % "LOG-MESSAGE FROM")
              (str/split-lines actual)))))

      (testing "locking-console + removing-levels"
        (let [actual (do-console-variant
                       (loggers/removing-levels
                         #{"INFO"}
                         (:locking loggers))
                       [origins messages])]
          (is (>= n (count (str/split-lines actual))))))


    (testing "async-console + mapping"
      (let [actual (do-console-variant
                     (loggers/mapping :msg (:async loggers))
                     [origins messages])]
        (is (every?
              #(.startsWith % "LOG-MESSAGE FROM")
              (str/split-lines actual)))))

    (testing "async-console + removing-levels"
      (let [actual (do-console-variant
                     (loggers/removing-levels
                       #{"INFO"}
                       (:async loggers))
                     [origins messages])]
        (is (>= n (count (str/split-lines actual))))))

    )
  )

