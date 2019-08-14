(ns flog.util-test
  (:require [flog.util :refer :all]
            [clojure.test :refer :all]))

(deftest agent-cb-tests
  (testing "Circuit-breaking agent"
    (let [dropped    (atom [])
          exceptions (atom [])
          [a wrap CBS-atom] (agent-cb {:v 0}
                                      [2 1000000000 3]
                                      2000 ;; open-timeout
                                      (fn [state & args]
                                        (swap! dropped conj state))
                                      (fn [ex ex-time nfails]
                                        (swap! exceptions conj ex)))
          inc-v* (fn [state & args]
                   (update state :v inc))]
      ;; no problems with those 2
      (send-off a (wrap #(update % :v inc)))
      (send-off a (wrap #(update % :v dec)))
      ;; first problem (divide-by-zero)
      (send-off a (wrap (fn [astate & args]
                          (update astate :v #(/ 1 %)))))
      ;(Thread/sleep 200)
      ;; still in CLOSED state
      ;; second problem (divide-by-zero)
      (send-off a (wrap (fn [astate & args]
                          (update astate :v #(/ 1 %)))))
      ;; now in OPEN state - we'll stay here for 2 seconds
      ;(Thread/sleep 100)
      ;(is (= :OPEN @CBS-atom))
      (future ;; these three will be dropped
        (send-off a (wrap inc-v*))
        (send-off a (wrap inc-v*))
        (send-off a (wrap inc-v*)))
      (Thread/sleep 2010) ;; this is important
      ;(is (= :HALF-OPEN @CBS-atom))

      ;; 3 consecutive successful calls will turn us back to CLOSED
      (send-off a (wrap inc-v*))
      (send-off a (wrap inc-v*))
      (send-off a (wrap inc-v*))
      ;(Thread/sleep 200)
      ;(is (= :CLOSED @CBS-atom))

      (is (= 3 (:v @a)))
      (is (every? (partial instance? ArithmeticException) @exceptions))
      (is (= 2 (count @exceptions)))
      ;; we'd see [1 2 3] had the calls not been dropped
      (is (= [0 0 0] (map :v @dropped)))

      )




    )
  )


