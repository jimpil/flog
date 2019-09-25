(defproject flog "0.2.1-SNAPSHOT"
  :description "Modern logging for Clojure"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[circuit-breaker-fn "0.1.4"]
                 [org.clojure/tools.logging "0.4.1"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  ;[org.clojure/tools.logging "0.4.1"]
                                  ]}}
  :repl-options {:init-ns flog.init}
  ;; nothing else needs to be AOTed
  :aot [flog.flogger-finder
        flog.http.authenticator]
  )
