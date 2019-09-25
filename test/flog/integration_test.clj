(ns flog.integration-test
  (:require [clojure.test :refer :all]
            [flog.core :as core]
            [flog.integration]
            [flog.appenders :as appenders])
  (:import (java.lang System$Logger System$Logger$Level)))

(deftest integration-tests
  (testing "Line number and file info"
    (let [out (atom [])]
      (with-open [logger (appenders/in-atom "TRACE" out)]
        (core/with-root-logger logger
          (let [jlogger (System/getLogger "whatever")]
            (.log jlogger System$Logger$Level/INFO "info")
            (is (= 14 (-> @out first :line))) ;; line above
            (is (.startsWith ^String (-> @out first :file)
                             "flog.integration_test$fn")) ;; this test-fn
            (is (= "info" (-> @out first :msg))) ;; was indeed logged

            )
          )
        )
      )
    )


  )
