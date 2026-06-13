(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'listora/again)
(def version "2.0.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (basis)
                :src-dirs  ["src"]
                :scm       {:url                 "https://github.com/liwp/again"
                            :connection          "scm:git:git://github.com/liwp/again.git"
                            :developerConnection "scm:git:ssh://git@github.com/liwp/again.git"
                            :tag                 (str "v" version)}
                :url       "https://github.com/liwp/again"})
  (b/copy-src {:src-dirs  ["src"]
               :class-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn deploy [_]
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
