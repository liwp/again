(ns again.core-test
  #+clj (:require [again.core :as a]
                  [clojure.test :as t :refer [is deftest]]
                  [clojure.test.check :as tc]
                  [clojure.test.check.clojure-test :refer [defspec]]
                  [clojure.test.check.generators :as gen]
                  [clojure.test.check.properties :as prop])
  #+cljs (:require [again.core :as a]
                   [cemerick.cljs.test :as t :refer-macros [is deftest]]
                   [clojure.test.check :as tc]
                   [clojure.test.check.clojure-test :refer-macros [defspec]]
                   [clojure.test.check.generators :as gen]
                   [clojure.test.check.properties :as prop :include-macros true]))

(defspec test-max-retries
  200
  (prop/for-all [n gen/s-pos-int]
                (let [s (a/max-retries n (repeat 0))]
                  (= (count s) n))))

(defspec test-max-delay
  200
  (prop/for-all [n gen/s-pos-int
                 m gen/s-pos-int]
                (let [s (a/max-retries n (a/max-delay m (a/linear-strategy 0 (/ 2 n))))]
                  (every? #(>= m %) s))))

(defspec test-max-duration
  200
  (prop/for-all [d gen/s-pos-int]
                (let [s (take (* 2 d) (a/max-duration d (a/constant-strategy 1)))]
                  (and (= (count s) d)
                       (= (reduce + s) d)))))

(defspec test-constant-strategy
  200
  (prop/for-all [n gen/s-pos-int
                 delay gen/pos-int]
                (let [s (a/max-retries n (a/constant-strategy delay))]
                  (and (= (count s) n)
                       (= (set s) #{delay})))))

(defspec test-immediate-strategy
  200
  (prop/for-all [n gen/s-pos-int]
                (let [s (a/max-retries n (a/immediate-strategy))]
                  (and (= (count s) n)
                       (= (set s) #{0})))))

(defspec test-linear-strategy
  200
  (prop/for-all [n gen/s-pos-int
                 initial-delay gen/pos-int
                 increment gen/pos-int]
                (let [s (a/max-retries n (a/linear-strategy initial-delay increment))
                      p (fn [[a b]] (= (- b a) increment))]
                  (and (= (count s) n)
                       (= (first s) initial-delay)
                       (every? p (partition 2 1 s))))))


(deftest test-stop-strategy
  (is (empty? (a/stop-strategy)) "stop strategy has no delays"))

