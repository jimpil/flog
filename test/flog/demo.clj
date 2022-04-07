(ns flog.demo
  (:require [flog.api.sync :as sync.log]
            [flog.api.async :as async.log]
            [flog.util :as util]
            [flog.context :as ctx]
            [clojure.walk :as walk]))

(util/set-level! :all)

(defonce error
  (IllegalStateException. "Internal error"))

(comment
  ;; the only difference between each pair should be the thread name

  (sync.log/info  "Hi there" :a 1 :b 2 :log/marker "foo")
  (async.log/info "Hi there" :a 1 :b 2)

  (ctx/with-mdc {"account-id" "foo"}
    (sync.log/info  "Hi there" :a 1 :b 2)
    (async.log/info "Hi there" :a 1 :b 2))

  ;; plain error
  (sync.log/error  error)
  (async.log/error error)
  ;; error followed by String
  (sync.log/error  error "Aborting...")
  (async.log/error error "Aborting...")
  ;; error followed by key-vals
  (sync.log/error  error :a 1 :b 2)
  (async.log/error error :a 1 :b 2)
  ;; error followed by String followed by key-vals
  (sync.log/error  error "Aborting..." :a 1 :b 2)
  (async.log/error error "Aborting..." :a 1 :b 2)
  ;; error followed by String followed by map
  (sync.log/error  error "Aborting..." {:a 1 :b 2})
  (async.log/error error "Aborting..." {:a 1 :b 2})
  ;; error followed by map
  (sync.log/error  error {:a 1 :b 2})
  (async.log/error error {:a 1 :b 2})

  (walk/macroexpand-all
    '(sync.log/debug "Hi there" :a 1 :b 2))

  (walk/macroexpand-all
    '(async.log/debug "Hi there" :a 1 :b 2))


  )

