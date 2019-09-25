(ns flog.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [flog.core :refer :all]
            [flog.loggers :refer :all]
            [flog.appenders :refer :all]
            [flog.util :as ut])
  )

(defn stress-test*
  [logger threads]
  (let [done? (atom false)
        total-logs (atom 0)
        lfns [#'flog.core/info
              #'flog.core/warn
              #'flog.core/debug
              #'flog.core/error
              #'flog.core/trace]]
    (dotimes [_ threads]
      (future
        (while (not @done?)
          (let [sleep-time (rand-int 200)
                level-fn (rand-nth lfns)]
            (Thread/sleep sleep-time)
            (level-fn logger "STRESS" "SOME LOG MESSAGE")
            (swap! total-logs inc')))))
    (Thread/sleep 60000)
    (reset! done? true)
    (println @total-logs))) ;; more than 2000 logs per second (around 120848 in total)


(deftest correct-logging
  (testing "No missing log-events"
    (let [logged-events (atom [])]
      (binding [flog.core/*root* (in-atom "DEBUG" logged-events)]
        (let [n 100]
          ;; spawn n futures
          (run!
            #(future
               (info "TEST" %))
            (repeat n "WHATEVER"))

          ;; allow a bit of time
          (Thread/sleep 500)

      (is (every?
            (fn [e]
              (every? (partial contains? e)
                      [:host :timestamp :thread :level :msg :file]))
            @logged-events))
      (is (= n (count @logged-events)))
      )))))
