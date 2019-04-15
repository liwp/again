(ns again.core-test
  (:require [again.core :as a :refer [with-retries]]
            [clojure.test :refer [is deftest testing]]
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
         p (fn [[a b]] (= (+ a increment) b))]
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

(defn new-failing-fn
  "Returns a map consisting of the following fields:
   - `f` - a function that will succeed on the `n`th call
   - `attempts` - an atom counting the number of executions of `f`
   - `exception` - the exception that `f` throws until it succeeds"
  [& [n]]
  (let [n (or n Integer/MAX_VALUE)
        attempts (atom 0)
        exception (Exception. "retry")
        f #(let [i (swap! attempts inc)]
             (if (< i n)
               (throw exception)
               i))]
    {:attempts attempts :exception exception :f f}))

(defn new-callback-fn
  "Returns a map consisting of the following fields:
   - `callback` - a callback function to pass to `with-retries`
   - `args` - an atom recording the arguments passed to `callback`"
  []
  (let [args (atom [])
        callback #(swap! args conj %)]
    {:args args :callback callback}))

(defmacro defsleeplesstest
  "Defines a test that removes any sleep delays"
  [n & body]
  `(deftest ~n
     (with-redefs [a/sleep (constantly nil)]
       ~@body)))

(defsleeplesstest test-with-retries
  (testing "with-retries"
    (testing "with non-nil return value"
      (is (= (with-retries [] :ok) :ok) "returns form value"))

    (testing "with nil return value"
      (is (nil? (with-retries [] nil)) "returns form value"))

    (testing "with user-context"
      (let [context {:a :b}
            {:keys [args callback]} (new-callback-fn)
            options {::a/callback callback
                     ::a/strategy []
                     ::a/user-context context}
            _ (with-retries options :ok)]
        (is (= (count @args) 1) "calls callback hook once")
        (is (= (::a/user-context (first @args)) context)
            "calls callback hook with user context")))

    (testing "with success on first try"
      (let [{:keys [attempts f]} (new-failing-fn 1)
            {:keys [args callback]} (new-callback-fn)]
        (with-retries
         {::a/callback callback
          ::a/strategy []}
         (f))
        (is (= @attempts 1) "executes operation once")
        (is (= (count @args) 1) "calls callback hook once")
        (is (= (first @args)
               {::a/attempts 1
                ::a/slept 0
                ::a/status :success})
            "calls callback hook with success")))

    (testing "with success on second try"
      (let [{:keys [attempts exception f]} (new-failing-fn 2)
            {:keys [args callback]} (new-callback-fn)]
        (with-retries
         {::a/callback callback
          ::a/strategy [12]}
         (f))
        (is (= @attempts 2) "executes operation twice")
        (is (= (count @args) 2) "calls callback hook twice")
        (is (= (first @args)
               {::a/attempts 1
                ::a/exception exception
                ::a/slept 0
                ::a/status :retry})
            "calls callback hook with failure")
        (is (= (second @args)
               {::a/attempts 2
                ::a/slept 12
                ::a/status :success})
            "calls callback hook with success")))

    (testing "with permanent failure"
      (let [{:keys [exception f]} (new-failing-fn)
            {:keys [args callback]} (new-callback-fn)]
        (is (thrown?
             Exception
             (with-retries
              {::a/callback callback
               ::a/strategy [123]}
              (f)))
            "throws exception")

        (is (= (count @args) 2) "calls callback hook twice")
        (is (= (first @args)
               {::a/attempts 1
                ::a/exception exception
                ::a/slept 0
                ::a/status :retry})
            "calls callback hook with failure")
        (is (= (second @args)
               {::a/attempts 2
                ::a/exception exception
                ::a/slept 123
                ::a/status :failure})
            "calls callback hook with permanent failure")))))

(defmulti log-attempt ::a/status)
(defmethod log-attempt :retry [s]
  (swap! (::a/user-context s) assoc :retried? true)
  (println "RETRY" s))
(defmethod log-attempt :success [s]
  (if (-> s ::a/user-context deref :retried?)
    (println "SUCCESS after" (::a/attempts s) "attempts" s)
    (println "SUCCESS on first attempt" s)))
(defmethod log-attempt :failure [s] (println "FAILURE" s))
(defmethod log-attempt :default [s] (assert false))

(defsleeplesstest test-multimethod-callback
  (testing "multi-method-callback"
    (testing "with failure"
      (let [{:keys [exception f]} (new-failing-fn)]
        (try
          (with-retries
           {::a/callback log-attempt
            ::a/strategy [1]
            ::a/user-context (atom {})}
           (f))
          (catch Exception e
            (when (not= e exception)
              (println "Unexpected exception:" e)
              (.printStackTrace e))))))

    (testing "with success"
      (let [{:keys [exception f]} (new-failing-fn 2)]
        (try
          (with-retries
           {::a/callback log-attempt
            ::a/strategy [1]
            ::a/user-context (atom {})}
           (f))
          (catch Exception e
            (when (not= e exception)
              (println "Unexpected exception:" e)
              (.printStackTrace e))))))))

(defsleeplesstest test-exception-filter
  (let [call-count (atom 0)
        retry-exception? (comp :retry? ex-data)

        test-conditional-retries
        (fn [expected-call-count strategy retry?]
          (try
            (reset! call-count 0)
            (with-retries {::a/strategy strategy
                           ::a/exception-predicate retry-exception?}
                          (do
                            (swap! call-count inc)
                            (throw (ex-info "failure" {:retry? retry?}))))
            (catch Exception e
              (is (= "failure" (.getMessage e)))
              (is (= expected-call-count @call-count)
                  (str "when retry is " retry?
                       " and strategy is " (pr-str strategy)
                       " should have tried " expected-call-count
                       " time(s)")))))]
    (test-conditional-retries 1 [1 2 3] false)
    (test-conditional-retries 1 [1 2 3 4] false)
    (test-conditional-retries 4 [1 2 3] true)
    (test-conditional-retries 5 [1 2 3 4] true)))

(defspec spec-with-retries
  200
  (prop/for-all
   [strategy (gen/vector gen/s-pos-int)]
   (let [{:keys [attempts f]} (new-failing-fn)
         delays (atom [])]
     (with-redefs [a/sleep #(swap! delays conj %)]
       (try
         (with-retries strategy (f))
         (catch Exception _)))

     (and (= @attempts (inc (count strategy)))
          (= @delays strategy)))))
