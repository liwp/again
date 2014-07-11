(ns again.core-test
  #+clj (:require [again.core :as a :refer [with-retries]]
                  [clojure.test :as t :refer [is deftest testing]]
                  [clojure.test.check :as tc]
                  [clojure.test.check.clojure-test :refer [defspec]]
                  [clojure.test.check.generators :as gen]
                  [clojure.test.check.properties :as prop])
  #+cljs (:require [again.core :as a]
                   [cemerick.cljs.test :as t :refer-macros [is deftest testing]]
                   [clojure.test.check :as tc]
                   [clojure.test.check.clojure-test :refer-macros [defspec]]
                   [clojure.test.check.generators :as gen]
                   [clojure.test.check.properties :as prop :include-macros true]))

(defspec spec-max-retries
  200
  (prop/for-all
   [n gen/s-pos-int]
   (let [s (a/max-retries n (repeat 0))]
     (= (count s) n))))

(defspec spec-max-delay
  200
  (prop/for-all
   [n gen/s-pos-int
    m gen/s-pos-int]
   (let [s (a/max-retries n (a/max-delay m (a/linear-strategy 0 (/ 2 n))))]
     (every? #(>= m %) s))))

(defspec spec-max-duration
  200
  (prop/for-all
   [d gen/s-pos-int]
   (let [s (take (* 2 d) (a/max-duration d (a/constant-strategy 1)))]
     (and (= (count s) d)
          (= (reduce + s) d)))))

(defspec spec-constant-strategy
  200
  (prop/for-all
   [n gen/s-pos-int
    delay gen/pos-int]
   (let [s (a/max-retries n (a/constant-strategy delay))]
     (and (= (count s) n)
          (= (set s) #{delay})))))

(defspec spec-immediate-strategy
  200
  (prop/for-all
   [n gen/s-pos-int]
   (let [s (a/max-retries n (a/immediate-strategy))]
     (and (= (count s) n)
          (= (set s) #{0})))))

(defspec spec-linear-strategy
  200
  (prop/for-all
   [n gen/s-pos-int
    initial-delay gen/pos-int
    increment gen/pos-int]
   (let [s (a/max-retries n (a/linear-strategy initial-delay increment))
         p (fn [[a b]] (= (- b a) increment))]
     (and (= (count s) n)
          (= (first s) initial-delay)
          (every? p (partition 2 1 s))))))


(deftest test-stop-strategy
  (is (empty? (a/stop-strategy)) "stop strategy has no delays"))

(defn set-up-call-count
  [& [i]]
  (let [i (or i Integer/MAX_VALUE)
        call-count (atom 0)
        call-count-fn (fn []
                        (let [r (swap! call-count inc)]
                          (if (< r i)
                            (throw (Exception. "retry"))
                            r)))]
    [call-count call-count-fn]))

(deftest test-with-retries
  (testing "with-retries"
    (testing "with non-nil return value"
      (is (= (with-retries [] :return-value) :return-value) "returns form value"))

    (testing "with nil return value"
      (is (nil? (with-retries [] nil)) "returns form value"))

    (testing "with success on first try"
      (let [call-count (atom 0)
            inc-count #(swap! call-count inc)]

        (with-retries [] (inc-count))

        (is (= @call-count 1) "executes operation once")))

    (testing "with success on second try"
      (let [[call-count call-count-fn] (set-up-call-count 2)]

        (with-retries [0 0] (call-count-fn))

        (is (= @call-count 2) "executes operation twice")))

    (testing "with permanent failure"
      (let [[_ fail-fn] (set-up-call-count)]
        (is (thrown? Exception (with-retries [] (fail-fn)))
            "throws exception")))))

(defspec spec-with-retries
  200
  (prop/for-all
   [n gen/s-pos-int]
   ;; TODO: we should pick a strategy at random since we're not actually sleeping
   (let [strategy (a/max-retries n (a/immediate-strategy))
         [call-count call-count-fn] (set-up-call-count)
         delays (atom [])]
     (with-redefs [a/sleep (fn [d] (swap! delays conj d))]
       (try
         (with-retries strategy (call-count-fn))
         (catch Exception _)))

     (and (= @call-count (inc n))
          (= @delays strategy)))))
