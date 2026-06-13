(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'listora/again)
(def version "2.0.0")
(def class-dir "target/classes")
;; Version-less jar name so the :deploy alias never needs a per-release edit;
;; the published version comes from the embedded pom, not the filename.
(def jar-file (format "target/%s.jar" (name lib)))

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
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))
