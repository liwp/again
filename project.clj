(defproject listora/again "0.1.0-SNAPSHOT"
  :description "A Clojure library for retrying operations."
  :url "https://github.com/listora/again"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src/cljx"]
  :jar-exclusions [#"\.cljx$"]
  :test-paths ["target/test-classes"]

  :profiles {:dev {:dependencies [[com.cemerick/double-check "0.5.7"]
                                  [org.clojure/clojure "1.6.0"]
                                  [org.clojure/clojurescript "0.0-2268"]]
                   :plugins [[com.keminglabs/cljx "0.4.0"]
                             [lein-cljsbuild "1.0.3"]
                             [com.cemerick/clojurescript.test "0.3.1"]]

                   :cljx {:builds [{:source-paths ["src/cljx"]
                                    :output-path "target/classes"
                                    :rules :clj}
                                   {:source-paths ["src/cljx"]
                                    :output-path "target/classes"
                                    :rules :cljs}
                                   {:source-paths ["test/cljx"]
                                    :output-path "target/test-classes"
                                    :rules :clj}
                                   {:source-paths ["test/cljx"]
                                    :output-path "target/test-classes"
                                    :rules :cljs}]}
                   :aliases {"cleantest" ["do" "clean," "cljx" "once," "test,"
                                          "cljsbuild" "test"]
                             "deploy2" ["do" "clean," "cljx" "once," "deploy" "clojars"]}}}

  :cljsbuild {:test-commands {"node" ["node" :node-runner
                                      "this.literal_js_was_evaluated=true"
                                      "target/testable.js"]}
              :builds [{:source-paths ["target/classes" "target/test-classes"]
                        :compiler {:libs [""]
                                   :optimizations :advanced
                                   :output-to "target/testable.js"
                                   :pretty-print true}}]})
