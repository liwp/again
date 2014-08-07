(defproject listora/again "0.1.0-SNAPSHOT"
  :description "A Clojure library for retrying operations."
  :url "https://github.com/listora/again"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/listora/again"}
  :deploy-repositories [["clojars" {:creds :gpg}]]
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.5.9"]
                                  [org.clojure/clojure "1.6.0"]]}})
