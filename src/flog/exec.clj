(ns flog.exec
  (:import (java.util.concurrent Executors)))

(def virtual
  (-> (Thread/ofVirtual)
      (.name "flog-" 0)
      .factory
      Executors/newThreadPerTaskExecutor))
