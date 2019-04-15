(ns again.core)

(defn constant-strategy
  "Generates a retry strategy with a constant delay (ms) between attempts, ie the
  delay is the same for each retry."
  [delay]
  {:pre [(>= delay 0)]}
  (repeat delay))

(defn immediate-strategy
  "Returns a retry strategy that retries without any delay."
  []
  (constant-strategy 0))

(defn additive-strategy
  "Returns a retry strategy where, after the `initial-delay` (ms), the delay
  increases by `increment` (ms) after each retry. The single argument version
  uses the given increment as both the initial delay and the increment."
  ([increment]
     (additive-strategy increment increment))
  ([initial-delay increment]
     {:pre [(>= initial-delay 0)
            (>= increment 0)]}
     (iterate #(+ increment %) (bigint initial-delay))))

(defn stop-strategy
  "A no-retries policy."
  []
  nil)

(defn multiplicative-strategy
  "Returns a retry strategy with exponentially increasing delays, ie each previous
  delay is multiplied by `delay-multiplier` to generate the next delay."
  [initial-delay delay-multiplier]
  {:pre [(<= 0 initial-delay)
         (<= 0 delay-multiplier)]}
  (iterate #(* delay-multiplier %) (bigint initial-delay)))

(defn- randomize-delay
  "Returns a random delay from the range [`delay` - `delta`, `delay` + `delta`],
  where `delta` is (`rand-factor` * `delay`). Note: return values are rounded to
  whole numbers, so eg (randomize-delay 0.8 1) can return 0, 1, or 2."
  [rand-factor delay]
  {:pre [(< 0 rand-factor 1)]}
  (let [delta (* delay rand-factor)
        min-delay (- delay delta)
        max-delay (+ delay delta)]
    ;; The inc is there so that if min-delay is 1 and max-delay is 3, then we
    ;; want a 1/3 chance for selecting 1, 2, or 3. Cast the delay to an int.
    (bigint (+ min-delay (* (rand) (inc (- max-delay min-delay)))))))

(defn randomize-strategy
  "Returns a retry strategy where all the delays have been scaled by a random
  number between [1 - `rand-factor`, 1 + `rand-factor`]. `rand-factor` must be
  greater than 0 and less than 1."
  [rand-factor retry-strategy]
  {:pre [(< 0 rand-factor 1)]}
  (map #(randomize-delay rand-factor %) retry-strategy))

(defn max-retries
  "Stop retrying after `n` retries."
  [n retry-strategy]
  {:pre [(>= n 0)]}
  (take n retry-strategy))

(defn clamp-delay
  "Replace delays in the strategy that are larger than `delay` with
  `delay`."
  [delay retry-strategy]
  {:pre [(>= delay 0)]}
  (map #(min delay %) retry-strategy))

(defn max-delay
  "Stop retrying once the a delay is larger than `delay`."
  [delay retry-strategy]
  {:pre [(>= delay 0)]}
  (take-while #(< % delay) retry-strategy))

(defn max-duration
  "Stop retrying once the sum of past delays exceeds `timeout` (ms). Note: the sum
  considers only the delays in the strategy, any time spent on executing the
  operation etc is not included (that is, we're not measuring wallclock time
  here)."
  [timeout retry-strategy]
  (when (and (pos? timeout) (seq retry-strategy))
    (let [[f & r] retry-strategy]
      (cons f
            (lazy-seq (max-duration (- timeout f) r))))))

(defn- sleep
  [delay]
  (Thread/sleep (long delay)))

(defn- build-options
  "Turn a strategy-sequence to an options-map (if it's not a map already), and
  define a default callback function."
  [strategy-or-options]
  (let [noop (fn [& _])
        default-options {::callback noop
                         ::exception-predicate (constantly true)}
        options (if (map? strategy-or-options)
                  strategy-or-options
                  {::strategy strategy-or-options})]
    (merge default-options options)))

(defn with-retries*
  [strategy-or-options f]
  (let [{callback ::callback
         delays ::strategy
         should-retry? ::exception-predicate
         :as options}
        (build-options strategy-or-options)

        callback-state (merge
                        {::attempts 1 ::slept 0}
                        (select-keys options [::user-context]))]
    (loop [[delay & delays] delays
           callback-state callback-state]
      (if-let [[res] (try
                       (let [res [(f)]]
                         (-> callback-state
                             (assoc ::status :success)
                             callback)
                         res)
                       (catch Exception e
                         (when-not (should-retry? e) (throw e))

                         (-> callback-state
                             (assoc
                              ::exception e
                              ::status (if delay :retry :failure))
                             callback)
                         (if delay
                           (sleep delay)
                           (throw e))
                         nil))]
        res
        (recur delays (-> callback-state
                          (update-in [::attempts] inc)
                          (update-in [::slept] + delay)))))))

(defmacro with-retries
  "Try executing `body`. If `body` throws an `Exception`, retry according to the
  retry `strategy`.

  A retry `strategy` is a seq of delays: `with-retries` will sleep the duration
  of the delay (in ms) before each retry. The total number of attempts is the
  number of elements in the `strategy` plus one. A simple retry stategy would
  be: [100 100 100 100] which results in the operation being retried four times,
  for a total of five attempts, with 100ms sleeps in between attempts. Note:
  that infinite strategies are supported, but maybe not encouraged…

  Strategies can be built with the provided builder fns, eg `linear-strategy`,
  and modified with the provided manipulator fns, eg `clamp-delay`, but you can
  also create any custom seq of delays that suits your use case.

  Instead of a simple delay sequence, you can also pass in the following type of
  options map:

  {:again.core/callback <fn>
   :again.core/user-context <anything, but probably an atom>
   :again.core/exception-predicate <fn>
   :again.core/strategy <delay strategy>}

  `:again.core/callback` is a callback function that is called after each
  attempt. `:again.core/user-context` is an opaque value that is passed to the
  callback function as an argument. And `:again.core/strategy` is the sequence
  of delays.

  The callback function is called with the following type of map as its only
  argument:

  {:again.core/attempts <the number of attempts thus far>
   :again.core/exception <the exception thrown by body>
   :again.core/slept <the sum of all delays thus far>
   :again.core/status <the result of the last attempt: :success, :failure, or :retry
   :again.core/user-context <the user context from the options map>}

   The exception-predicate is a function that is called with an exception. It
   can return truthy or falsey. If truthy, the exception is retried. This
   function defaults to `(constantly true)`"
  [strategy-or-options & body]
  `(with-retries* ~strategy-or-options (fn [] ~@body)))
