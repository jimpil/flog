(defproject com.github.jimpil/flog "0.3.8-SNAPSHOT"
  :description "Structured-logging facilities geared towards Clojure maps (backed by Log4j2/3)."
  :url "https://github.com/jimpil/flog"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1" :scope "provided"]
                 [org.apache.logging.log4j/log4j-core "3.0.0-beta1" :scope "provided"]
                 [org.apache.logging.log4j/log4j-api "3.0.0-beta1" :scope "provided"]
                 [org.clojure/tools.logging "1.2.4"]]

  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  :javac-options ["-target" "17" "-source" "17"]
  :jar-exclusions [#"log4j2.xml"]

  ;; sample property for including location-info
  :jvm-opts ["-Dflog.builder/include-location-info?=true" "--enable-preview"]
  :repl-options {:init-ns flog.demo}

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["deploy" ]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ;["vcs" "push"]
                  ]
  :deploy-repositories [["releases" :clojars]] ;; lein release :patch
  :signing {:gpg-key "jimpil1985@gmail.com"}
  )
