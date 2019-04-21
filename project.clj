(defproject listora/again "1.0.0-SNAPSHOT"
  :description "A Clojure library for retrying operations."
  :url "https://github.com/listora/again"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/liwp/again"}
  :deploy-repositories [["releases" :clojars]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]
                                  [org.clojure/clojure "1.9.0"]]}})
