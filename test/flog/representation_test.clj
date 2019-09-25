(ns flog.representation-test
  (:require [clojure.test :refer :all]
            [flog.init :as init]
            [flog.appenders :as appenders]
            [flog.core :as core])
  (:import (java.io StringReader)))

(def dummy-credentials
  (constantly ["user" "password"]))

(defn- print<=>read
  [logger]
  (let [printed-logger (pr-str {:flogger logger})
        round-tripped (-> printed-logger
                          StringReader.
                          init/read-config
                          pr-str)]
    (= printed-logger round-tripped)))

(deftest printing-to-reading-and-back
  (testing "appenders"
    (testing "PassThroughWriter"
      (let [logger (appenders/map->PassThroughWriter {:level "DEBUG"
                                                      :out "/tmp/sink.txt"})]
        (is (print<=>read logger))))

    (testing "StdOut"
      (let [logger (appenders/map->StdOut {:level "TRACE"})]
        (is (print<=>read logger))))

    (testing "StdErr"
      (let [logger (appenders/map->StdErr {:level "WARN"})]
        (is (print<=>read logger))))

    (testing "AtomOut"
      (let [logger (appenders/map->AtomOut {:level "INFO"})]
        (is (print<=>read logger))))

    (testing "HttpPost"
      (let [logger (appenders/map->HttpPost {:level "INFO"
                                             :timeout 2 ;; seconds
                                             :url "https://whatever.com"
                                             :client {:thread-pool [:solo 0]
                                                      :redirect-policy :normal
                                                      :version :http1.1
                                                      :priority 1
                                                      :connect-timeout 1
                                                      :proxy nil ;;{:keys [host port] :as proxy}
                                                      :credentials 'flog.representation-test/dummy-credentials
                                                      ;:json-str    'clojure.data.json/write-str
                                                      }})]
        (is (print<=>read logger))))
    )
  )
