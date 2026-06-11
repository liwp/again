(ns again.core-test
  (:require [again.core :as a :refer [with-retries]]
            [clojure.test :refer [is deftest testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(use-fixtures :each (fn [f] (Thread/interrupted) (try (f) (finally (Thread/interrupted)))))

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
  - `callback` - a callback function to pass to `with-retries` that will fail
  the operation early after the `n`th call
  - `args` - an atom recording the arguments passed to `callback`"
  [& [n]]
  (let [n (or n Integer/MAX_VALUE)
        attempts (atom 0)
        args (atom [])
        callback #(let [i (swap! attempts inc)]
                    (swap! args conj %)
                    (when (< n i)
                      ::a/fail))]
    {:args args :callback callback}))

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

(deftest test-with-retries
  (with-redefs [a/sleep (constantly nil)]
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
              "calls callback hook with permanent failure")))

      (testing "with early failure"
        (let [{:keys [exception f]} (new-failing-fn)
              {:keys [args callback]} (new-callback-fn 1)]
          (is (thrown?
               Exception
               (with-retries
                 {::a/callback callback
                  ::a/strategy [1 2 3]}
                 (f)))
              "throws exception")

          (is (= (count @args) 2) "calls callback hook three twice")
          (is (= (first @args)
                 {::a/attempts 1
                  ::a/exception exception
                  ::a/slept 0
                  ::a/status :retry})
              "first callback call")
          (is (= (second @args)
                 {::a/attempts 2
                  ::a/exception exception
                  ::a/slept 1
                  ::a/status :retry})
              "last callback call"))))))

(defmulti log-attempt ::a/status)

(defmethod log-attempt :retry [s]
  (if (< (count @(::a/user-context s)) 1)
    (swap! (::a/user-context s) conj :retry)
    (do
      (swap! (::a/user-context s) conj :fail)
      ::a/fail)))

(defmethod log-attempt :success [s]
  (swap! (::a/user-context s) conj :success))

(defmethod log-attempt :failure [s]
  (swap! (::a/user-context s) conj :failure))

(defmethod log-attempt :default [s] (assert false))

(deftest test-preconditions
  (testing "constant-strategy rejects negative delay"
    (is (thrown? AssertionError (a/constant-strategy -1))))
  (testing "additive-strategy rejects negative initial-delay"
    (is (thrown? AssertionError (a/additive-strategy -1 100))))
  (testing "additive-strategy rejects negative increment"
    (is (thrown? AssertionError (a/additive-strategy 100 -1))))
  (testing "multiplicative-strategy rejects negative initial-delay"
    (is (thrown? AssertionError (a/multiplicative-strategy -1 2.0))))
  (testing "multiplicative-strategy rejects negative multiplier"
    (is (thrown? AssertionError (a/multiplicative-strategy 100 -1.0))))
  (testing "max-retries rejects negative n"
    (is (thrown? AssertionError (a/max-retries -1 (a/constant-strategy 0)))))
  (testing "clamp-delay rejects negative delay"
    (is (thrown? AssertionError (a/clamp-delay -1 (a/constant-strategy 0)))))
  (testing "max-delay rejects negative delay"
    (is (thrown? AssertionError (a/max-delay -1 (a/constant-strategy 0)))))
  (testing "max-wall-clock-duration rejects negative timeout"
    (is (thrown? AssertionError (a/max-wall-clock-duration -1 (a/constant-strategy 100)))))
  (testing "max-wall-clock-duration rejects nested max-wall-clock-duration"
    (is (thrown? AssertionError
                 (a/max-wall-clock-duration 5000 (a/max-wall-clock-duration 3000 (a/constant-strategy 100))))))
  (let [s (a/max-retries 1 (a/constant-strategy 100))]
    (testing "randomize-strategy rejects rand-factor of 0"
      (is (thrown? AssertionError (a/randomize-strategy 0 s))))
    (testing "randomize-strategy rejects rand-factor of 1"
      (is (thrown? AssertionError (a/randomize-strategy 1 s))))
    (testing "randomize-strategy rejects rand-factor greater than 1"
      (is (thrown? AssertionError (a/randomize-strategy 1.5 s))))))

(deftest test-zero-and-boundary-values
  (testing "max-retries 0 returns empty strategy"
    (is (empty? (a/max-retries 0 (a/constant-strategy 100)))))
  (testing "max-delay 0 returns empty strategy"
    (is (empty? (a/max-delay 0 (a/additive-strategy 0 1)))))
  (testing "max-duration with zero timeout returns nil"
    (is (nil? (a/max-duration 0 (a/constant-strategy 1)))))
  (testing "max-duration with empty strategy returns nil"
    (is (nil? (a/max-duration 1000 []))))
  (testing "multiplicative-strategy with initial-delay 0 produces all-zero delays"
    (is (every? zero? (take 5 (a/multiplicative-strategy 0 2.0)))))
  (testing "multiplicative-strategy with multiplier 1 produces constant delays"
    (is (every? #(== % 100) (take 5 (a/multiplicative-strategy 100 1.0))))))

(deftest test-exception-identity
  (with-redefs [a/sleep (constantly nil)]
    (testing "rethrows the exact same exception object"
      (let [{:keys [exception f]} (new-failing-fn)]
        (is (identical? exception
                        (try
                          (with-retries [100] (f))
                          (catch Exception e e))))))))

(deftest test-stop-strategy-no-retries
  (with-redefs [a/sleep (constantly nil)]
    (let [{:keys [attempts exception f]} (new-failing-fn)]
      (is (identical? exception
                      (try (with-retries (a/stop-strategy) (f))
                           (catch Exception e e))))
      (is (= @attempts 1) "operation attempted exactly once with stop-strategy"))))

(deftest test-readme-basic-example
  (let [{:keys [attempts f]} (new-failing-fn 4)
        slept (atom [])]
    (with-redefs [a/sleep #(swap! slept conj %)]
      (with-retries [100 1000 10000] (f))
      (is (= @attempts 4) "operation attempted four times")
      (is (= @slept [100 1000 10000]) "sleeps match strategy delays"))))

(deftest test-multimethod-callback
  (with-redefs [a/sleep (constantly nil)]
    (testing "multi-method-callback"
      (testing "with success"
        (let [{:keys [f]} (new-failing-fn 2)
              user-context (atom [])]
          (with-retries
            {::a/callback log-attempt
             ::a/strategy [1 2]
             ::a/user-context user-context}
            (f))
          (is (= (count @user-context) 2) "multimethod is called twice")
          (is (= (first @user-context) :retry) "first call is a retry")
          (is (= (second @user-context) :success) "second call is a success")))

      (testing "with failure"
        (let [{:keys [exception f]} (new-failing-fn)
              user-context (atom [])]
          (try
            (with-retries
              {::a/callback log-attempt
               ::a/strategy [1]
               ::a/user-context user-context}
              (f))
            (catch Exception e
              (is (= e exception) "Unexpected exception")))
          (is (= (count @user-context) 2) "multimethod is called twice")
          (is (= (first @user-context) :retry) "first call is a retry")
          (is (= (second @user-context) :failure) "second call is a failure")))

      (testing "with early failure"
        (let [{:keys [exception f]} (new-failing-fn)
              user-context (atom [])]
          (try
            (with-retries
              {::a/callback log-attempt
               ::a/strategy [1 2]
               ::a/user-context user-context}
              (f))
            (catch Exception e
              (is (= e exception) "Unexpected exception")))
          (is (= (count @user-context) 2) "multimethod is called three times")
          (is (= (first @user-context) :retry) "first call is a retry")
          (is (= (second @user-context) :fail) "second call is a fail"))))))

(deftest test-max-wall-clock-duration-runtime
  (with-redefs [a/sleep (constantly nil)]
    (testing "deadline exceeded after first failure stops retrying"
      (let [calls    (atom 0)
            {:keys [f attempts]} (new-failing-fn)]
        (with-redefs [a/current-time-ms #(case (long (swap! calls inc)) 1 0 11000)]
          (is (thrown? Exception
                       (with-retries (a/max-wall-clock-duration 10000 [100 100 100]) (f)))))
        (is (= @attempts 1) "only 1 attempt before wall-clock timeout fires")))

    (testing "timeout not yet reached — all retries in strategy proceed"
      (let [{:keys [f attempts]} (new-failing-fn)]
        (with-redefs [a/current-time-ms (constantly 0)]
          (try
            (with-retries (a/max-wall-clock-duration 10000 [100 100 100]) (f))
            (catch Exception _)))
        (is (= @attempts 4) "all 3 retries fire; 4 total attempts before strategy exhaustion")))

    (testing "zero timeout stops after first failure"
      (let [{:keys [f attempts]} (new-failing-fn)]
        (with-redefs [a/current-time-ms (constantly 0)]
          (is (thrown? Exception
                       (with-retries (a/max-wall-clock-duration 0 [100 100 100]) (f)))))
        (is (= @attempts 1) "only 1 attempt with zero timeout")))

    (testing "success path is unaffected"
      (let [{:keys [f]} (new-failing-fn 1)]
        (with-redefs [a/current-time-ms (constantly 0)]
          (is (= 1 (with-retries (a/max-wall-clock-duration 10000 [100]) (f)))
              "returns result when operation succeeds"))))

    (testing "strategy exhausted before timeout still throws"
      (let [{:keys [f attempts]} (new-failing-fn)]
        (with-redefs [a/current-time-ms (constantly 0)]
          (is (thrown? Exception
                       (with-retries (a/max-wall-clock-duration 10000 [100]) (f)))))
        (is (= @attempts 2) "1 initial + 1 retry before strategy runs out")))

    (testing "callback receives :failure status when wall-clock timeout fires"
      (let [calls    (atom 0)
            {:keys [f]} (new-failing-fn)
            {:keys [args callback]} (new-callback-fn)]
        (with-redefs [a/current-time-ms #(case (long (swap! calls inc)) 1 0 11000)]
          (is (thrown? Exception
                       (with-retries (merge (a/max-wall-clock-duration 10000 [100 100])
                                            {::a/callback callback})
                         (f)))))
        (is (= :failure (::a/status (last @args)))
            "last callback call has :failure status on wall-clock timeout")))))

(deftest test-interrupted-exception-not-retried
  (let [attempts (atom 0)]
    (with-redefs [a/sleep (constantly nil)]
      (try
        (with-retries [100 200]
          (swap! attempts inc)
          (throw (InterruptedException.)))
        (catch InterruptedException _)))
    (is (= @attempts 1) "InterruptedException should not trigger a retry")))

(deftest test-interrupted-exception-restores-flag
  (with-redefs [a/sleep (constantly nil)]
    (try
      (with-retries [100 200]
        (throw (InterruptedException.)))
      (catch InterruptedException _
        (is (.isInterrupted (Thread/currentThread))
            "interrupt flag must be restored when InterruptedException propagates")))))

(deftest test-interrupted-exception-callback-status
  (let [callback-args (atom [])]
    (with-redefs [a/sleep (constantly nil)]
      (try
        (with-retries
          {::a/callback #(swap! callback-args conj %)
           ::a/strategy [100 200]}
          (throw (InterruptedException.)))
        (catch InterruptedException _)))
    (is (= 1 (count @callback-args)) "callback is called once")
    (is (= :interrupted (::a/status (first @callback-args)))
        "callback receives :interrupted status")))

(deftest test-sleep-restores-interrupt-flag
  (let [sleep-fn    #'again.core/sleep
        test-thread (Thread/currentThread)]
    (future (Thread/sleep 20) (.interrupt test-thread))
    (try
      (sleep-fn 10000)
      (catch InterruptedException _
        (is (.isInterrupted (Thread/currentThread))
            "sleep must restore the interrupt flag before rethrowing")))))

(deftest test-max-wall-clock-duration-shape
  (let [strategy [100 200 300]
        result   (a/max-wall-clock-duration 5000 strategy)]
    (is (map? result) "returns a map")
    (is (= strategy (::a/strategy result)) "strategy is preserved")
    (is (= 5000 (::a/wall-clock-timeout result)) "timeout is stored")))

(defspec spec-max-wall-clock-duration
  200
  (prop/for-all
   [n       (gen/choose 1 10)
    timeout gen/s-pos-int]
   ;; Clock advances by (timeout+1) on every call, so after the first failure
   ;; elapsed >= (timeout+1) > timeout. Exactly 1 attempt is made regardless
   ;; of strategy length.
   (let [calls   (atom 0)
         {:keys [f attempts]} (new-failing-fn)]
     (with-redefs [a/sleep (constantly nil)
                   a/current-time-ms #(* (inc timeout) (swap! calls inc))]
       (try
         (with-retries (a/max-wall-clock-duration timeout (take n (repeat 0))) (f))
         (catch Exception _)))
     (= @attempts 1))))

(deftest test-consecutive-failures-policy
  (testing "does not trip before reaching the threshold of consecutive failures"
    (let [p0 (a/consecutive-failures 3)
          p1 (a/observe p0 :failure 0)
          p2 (a/observe p1 :failure 0)]
      (is (not (a/tripped? p0 0)))
      (is (not (a/tripped? p1 0)))
      (is (not (a/tripped? p2 0)))))

  (testing "trips on the threshold-th consecutive failure"
    (let [p (-> (a/consecutive-failures 3)
                (a/observe :failure 0)
                (a/observe :failure 0)
                (a/observe :failure 0))]
      (is (a/tripped? p 0))))

  (testing "a success resets the failure count"
    (let [p (-> (a/consecutive-failures 2)
                (a/observe :failure 0)
                (a/observe :success 0)
                (a/observe :failure 0))]
      (is (not (a/tripped? p 0)))))

  (testing "reset clears accumulated failures and returns a working policy"
    (let [p (-> (a/consecutive-failures 2)
                (a/observe :failure 0)
                (a/observe :failure 0))]
      (is (a/tripped? p 0))
      (let [p' (a/reset p)]
        (is (not (a/tripped? p' 0)))
        (is (a/tripped? (-> p'
                            (a/observe :failure 0)
                            (a/observe :failure 0))
                        0)
            "failures accumulate again after reset"))))

  (testing "rejects a non-positive threshold"
    (is (thrown? AssertionError (a/consecutive-failures 0)))
    (is (thrown? AssertionError (a/consecutive-failures -1)))))

(deftest test-circuit-breaker-constructor
  (testing "a new breaker starts closed"
    (is (= :closed (a/circuit-state (a/circuit-breaker (a/consecutive-failures 3))))))

  (testing "reset-timeout defaults to 60000 ms"
    (is (= 60000 (::a/reset-timeout (a/circuit-breaker (a/consecutive-failures 3))))))

  (testing "reset-timeout can be overridden"
    (is (= 5000 (::a/reset-timeout
                 (a/circuit-breaker (a/consecutive-failures 3) {::a/reset-timeout 5000})))))

  (testing "on-event defaults to a function"
    (is (fn? (::a/on-event (a/circuit-breaker (a/consecutive-failures 3))))))

  (testing "user-context is absent unless supplied, and present when supplied"
    (is (not (contains? (a/circuit-breaker (a/consecutive-failures 3)) ::a/user-context)))
    (is (= :ctx (::a/user-context
                 (a/circuit-breaker (a/consecutive-failures 3) {::a/user-context :ctx})))))

  (testing "rejects a value that is not a BreakerPolicy"
    (is (thrown? AssertionError (a/circuit-breaker :not-a-policy)))))

(deftest test-circuit-breaker-closed
  (testing "a successful call returns its value and stays closed"
    (let [cb (a/circuit-breaker (a/consecutive-failures 3))]
      (is (= :ok (a/with-circuit-breaker cb :ok)))
      (is (= :closed (a/circuit-state cb)))))

  (testing "a failing call rethrows and stays closed below the threshold"
    (let [cb   (a/circuit-breaker (a/consecutive-failures 3))
          boom (Exception. "boom")]
      (is (thrown? Exception (a/with-circuit-breaker cb (throw boom))))
      (is (= :closed (a/circuit-state cb))))))

(deftest test-circuit-breaker-trips-open
  (let [cb   (a/circuit-breaker (a/consecutive-failures 2))
        boom (Exception. "boom")
        fail #(a/with-circuit-breaker cb (throw boom))]
    (is (thrown? Exception (fail)))
    (is (= :closed (a/circuit-state cb)) "one failure does not trip a threshold-2 breaker")
    (is (thrown? Exception (fail)))
    (is (= :open (a/circuit-state cb)) "the second consecutive failure trips it open")))

(deftest test-circuit-breaker-success-resets-count
  (let [cb   (a/circuit-breaker (a/consecutive-failures 2))
        boom (Exception. "boom")]
    (is (thrown? Exception (a/with-circuit-breaker cb (throw boom))))
    (is (= :ok (a/with-circuit-breaker cb :ok)))
    (is (thrown? Exception (a/with-circuit-breaker cb (throw boom))))
    (is (= :closed (a/circuit-state cb)) "the success reset the count, so one failure does not trip")))

(deftest test-circuit-breaker-open-short-circuits
  (let [cb    (a/circuit-breaker (a/consecutive-failures 1))
        boom  (Exception. "boom")
        calls (atom 0)]
    (is (thrown? Exception (a/with-circuit-breaker cb (throw boom))))
    (is (= :open (a/circuit-state cb)))
    (let [e (try (a/with-circuit-breaker cb (swap! calls inc) :never)
                 (catch Exception ex ex))]
      (is (a/circuit-open? e) "an open breaker throws a circuit-open exception")
      (is (= 0 @calls) "the wrapped body is not executed while open"))))

(deftest test-circuit-breaker-half-open-probe-closes
  (let [clock (atom 0)]
    (with-redefs [a/current-time-ms (fn [] @clock)]
      (let [cb   (a/circuit-breaker (a/consecutive-failures 1) {::a/reset-timeout 1000})
            boom (Exception. "boom")]
        (is (thrown? Exception (a/with-circuit-breaker cb (throw boom))))
        (is (= :open (a/circuit-state cb)))

        (reset! clock 500)
        (is (a/circuit-open? (try (a/with-circuit-breaker cb :nope) (catch Exception e e)))
            "still short-circuits before the reset timeout elapses")
        (is (= :open (a/circuit-state cb)))

        (reset! clock 1000)
        (is (= :ok (a/with-circuit-breaker cb :ok)) "admits a probe once the timeout elapses")
        (is (= :closed (a/circuit-state cb)) "a successful probe closes the breaker")))))

(deftest test-circuit-breaker-half-open-failure-reopens
  (let [clock (atom 0)]
    (with-redefs [a/current-time-ms (fn [] @clock)]
      (let [cb   (a/circuit-breaker (a/consecutive-failures 1) {::a/reset-timeout 1000})
            boom (Exception. "boom")]
        (is (thrown? Exception (a/with-circuit-breaker cb (throw boom))))

        (reset! clock 1000)
        (is (thrown? Exception (a/with-circuit-breaker cb (throw boom)))
            "the probe runs but fails")
        (is (= :open (a/circuit-state cb)) "a failed probe re-opens the breaker")

        (reset! clock 1500)
        (is (a/circuit-open? (try (a/with-circuit-breaker cb :nope) (catch Exception e e)))
            "opened-at was restamped, so the timeout window restarts")

        (reset! clock 2000)
        (is (= :ok (a/with-circuit-breaker cb :ok)))
        (is (= :closed (a/circuit-state cb)))))))

(deftest test-circuit-breaker-half-open-single-probe
  (let [clock  (atom 0)
        allow! #'again.core/allow!]
    (with-redefs [a/current-time-ms (fn [] @clock)]
      (let [cb   (a/circuit-breaker (a/consecutive-failures 1) {::a/reset-timeout 1000})
            boom (Exception. "boom")]
        (is (thrown? Exception (a/with-circuit-breaker cb (throw boom))))
        (reset! clock 1000)
        (is (= true (::a/permitted? (allow! cb))) "first caller is admitted as the probe")
        (is (= :half-open (a/circuit-state cb)))
        (is (= false (::a/permitted? (allow! cb)))
            "a second caller is denied while the probe is in flight")))))

(deftest test-circuit-breaker-interrupt-not-recorded
  (testing "InterruptedException does not count as a failure"
    (let [cb (a/circuit-breaker (a/consecutive-failures 1))]
      (try
        (a/with-circuit-breaker cb (throw (InterruptedException.)))
        (catch InterruptedException _))
      (is (= :closed (a/circuit-state cb)) "an interrupt did not trip the breaker")))

  (testing "the interrupt flag is restored before the exception propagates"
    (let [cb (a/circuit-breaker (a/consecutive-failures 1))]
      (try
        (a/with-circuit-breaker cb (throw (InterruptedException.)))
        (catch InterruptedException _
          (is (.isInterrupted (Thread/currentThread))))))))

(deftest test-circuit-breaker-events
  (let [clock (atom 0)]
    (with-redefs [a/current-time-ms (fn [] @clock)]
      (let [events  (atom [])
            cb      (a/circuit-breaker (a/consecutive-failures 1)
                                       {::a/reset-timeout 1000
                                        ::a/on-event      #(swap! events conj %)})
            boom    (Exception. "boom")]
        (a/with-circuit-breaker cb :ok)
        (is (thrown? Exception (a/with-circuit-breaker cb (throw boom))))
        (is (a/circuit-open? (try (a/with-circuit-breaker cb :nope) (catch Exception e e))))

        (let [evs @events]
          (is (= [:success :failure :state-change :short-circuit]
                 (map ::a/event evs))
              "fires success, then failure + the resulting state-change, then short-circuit")
          (let [sc (first (filter #(= :state-change (::a/event %)) evs))]
            (is (= :closed (::a/from sc)))
            (is (= :open (::a/to sc))))
          (let [f (first (filter #(= :failure (::a/event %)) evs))]
            (is (= boom (::a/exception f)) "the failure event carries the exception")))))))

(deftest test-circuit-breaker-events-user-context
  (let [events (atom [])
        ctx    {:name "svc"}
        cb     (a/circuit-breaker (a/consecutive-failures 3)
                                  {::a/on-event     #(swap! events conj %)
                                   ::a/user-context ctx})]
    (a/with-circuit-breaker cb :ok)
    (is (= ctx (::a/user-context (first @events))) "user-context is attached to events")))

(deftest test-circuit-breaker-with-retries-integration
  (with-redefs [a/sleep (constantly nil)]
    (testing "breaker-innermost counts each attempt; a ::fail callback stops retrying once open"
      (let [cb           (a/circuit-breaker (a/consecutive-failures 3))
            boom         (Exception. "boom")
            attempts     (atom 0)
            stop-on-open (fn [{e ::a/exception}]
                           (when (and e (a/circuit-open? e)) ::a/fail))]
        (try
          (with-retries
            {::a/strategy (repeat 10 1)
             ::a/callback stop-on-open}
            (a/with-circuit-breaker cb
              (swap! attempts inc)
              (throw boom)))
          (catch Exception _))
        (is (= :open (a/circuit-state cb)) "the breaker opened during the retries")
        (is (= 3 @attempts)
            "the body ran 3 times (each attempt counted); attempt 4 short-circuited and the callback stopped the loop")))))
