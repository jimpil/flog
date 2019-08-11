(ns flog.expansions
  (:require
    [flog.loggers :refer :all]
    [flog.core :refer :all]))

(defn boo []
  (binding [flog.core/*root*
            (locking-console "DEBUG")]
    (info "TEST" "whateva"))
  )

(comment

  (binding [flog.core/*root*
            (locking-console "DEBUG")]
    #_(macroexpand
      '(info {:key "foo"} "TEST" "whateva")
      )

    (macroexpand
      (tracef "Root logger initialised per profile:%s%s"
               (str \newline)
               {:A :B})
      )

    #_(macroexpand
      '(info {} "TEST" "whateva")
      )
    )











  )