(ns again.core-test
  (:require [again.core :as a :refer [with-retries]]
            [clojure.test :as t :refer [is deftest testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defspec spec-max-retries
  200
  (prop/for-all
   [n gen/s-pos-int]
   (let [s (a/max-retries n (repeat 0))]
     (= (count s) n))))

(defspec spec-clamp-delay
  200
  (prop/for-all
   [n gen/s-pos-int
    max-delay gen/s-pos-int]
   (let [s (a/max-retries
            n
            ;; The increment is picked so that we'll cross max-delay on delay 3
            (a/clamp-delay max-delay (a/additive-strategy 0 (/ max-delay 2))))]
     (every? #(<= % max-delay) s))))

(defspec spec-max-delay
  200
  (prop/for-all
   [n gen/s-pos-int
    max-delay gen/s-pos-int]
   (let [s (a/max-retries
            n
            (a/max-delay max-delay (a/additive-strategy 0 (/ max-delay 10))))]
     (and (= (count s) (min n 10))
          (every? #(<= % max-delay) s)))))

(defspec spec-max-duration
  200
  (prop/for-all
   [d gen/s-pos-int]
   (let [s (take (* 2 d) (a/max-duration d (a/constant-strategy 1)))]
     (and (= (count s) d)
          (= (reduce + s) d)))))

(deftest test-max-duration
  (testing "with not enough delays to satisfy specified duration"
    (is (= (a/max-duration 10000 [0]) [0]))))

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

(defspec spec-additive-strategy
  200
  (prop/for-all
   [n gen/s-pos-int
    initial-delay gen/pos-int
    increment gen/pos-int]
   (let [s (a/max-retries n (a/additive-strategy initial-delay increment))
         p (fn [[a b]] (= (+ a increment)  b))]
     (and (= (count s) n)
          (= (first s) initial-delay)
          (every? p (partition 2 1 s))))))

(defspec spec-multiplicative-strategy
  200
  (prop/for-all
   [n gen/s-pos-int
    initial-delay gen/s-pos-int
    delay-multiplier (gen/elements [1.0 1.1 1.3 1.6 2.0 3.0 5.0 9.0 14.0 20.0])]
   (let [s (a/max-retries
            n
            (a/multiplicative-strategy initial-delay delay-multiplier))
         p (fn [[a b]] (= (* a delay-multiplier) b))]
     (and (= (count s) n)
          (= (first s) initial-delay)
          (every? p (partition 2 1 s))))))

(defspec spec-randomize-delay
  200
  (prop/for-all
   [rand-factor (gen/elements [0.1 0.2 0.3 0.4 0.5 0.6 0.7 0.8 0.9])
    delay gen/s-pos-int]
   (let [randomize-delay #'again.core/randomize-delay
         min-delay (bigint (* delay (- 1 rand-factor)))
         max-delay (bigint (inc (* delay (+ 1 rand-factor))))
         rand-delay (randomize-delay rand-factor delay)]
     (and (<= 0 rand-delay)
          (<= min-delay rand-delay max-delay)))))

(defspec spec-randomize-strategy
  200
  (prop/for-all
   [n gen/s-pos-int
    rand-factor (gen/elements [0.1 0.2 0.3 0.4 0.5 0.6 0.7 0.8 0.9])]
   (let [initial-delay 1000
         s (a/max-retries
            n
            (a/randomize-strategy
             rand-factor
             (a/constant-strategy initial-delay)))
         min-delay (bigint (* initial-delay (- 1 rand-factor)))
         max-delay (bigint (inc (* initial-delay (+ 1 rand-factor))))]
     (every? #(<= min-delay % max-delay) s))))

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
   (let [strategy (a/max-retries n (a/immediate-strategy))
         [call-count call-count-fn] (set-up-call-count)
         delays (atom [])]
     (with-redefs [a/sleep (fn [d] (swap! delays conj d))]
       (try
         (with-retries strategy (call-count-fn))
         (catch Exception _)))

     (and (= @call-count (inc n))
          (= @delays strategy)))))
