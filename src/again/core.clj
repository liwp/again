(ns again.core)

(defn constant-strategy
  "Generates a retry strategy with a constant delay (ms) between
  retries, ie the delay is the same for each retry."
  [delay]
  {:pre [(>= delay 0)]}
  (repeat delay))

(defn immediate-strategy
  "Returns a retry strategy that retries without any delay."
  []
  (constant-strategy 0))

(defn additive-strategy
  "Returns a retry strategy where, after the `initial-delay` (ms), the
  delay increases by `increment` (ms) after each retry. The single
  argument version uses the given increment as both the initial delay
  and the increment."
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
  "Returns a retry strategy with exponentially increasing delays, ie
  each previous delay is multiplied by delay-multiplier to generate
  the next delay."
  [initial-delay delay-multiplier]
  {:pre [(<= 0 initial-delay)
         (<= 0 delay-multiplier)]}
  (iterate #(* delay-multiplier %) (bigint initial-delay)))

(defn- randomize-delay
  "Returns a random delay from the range [`delay` - `delta`, `delay` + `delta`],
  where `delta` is (`rand-factor` * `delay`). Note: return values are
  rounded to whole numbers, so eg (randomize-delay 0.8 1) can return
  0, 1, or 2."
  [rand-factor delay]
  {:pre [(< 0 rand-factor 1)]}
  (let [delta (* delay rand-factor)
        min-delay (- delay delta)
        max-delay (+ delay delta)]
    ;; The inc is there so that if min-delay is 1 and max-delay is 3,
    ;; then we want a 1/3 chance for selecting 1, 2, or 3.
    ;; Cast the delay to an int.
    (bigint (+ min-delay (* (rand) (inc (- max-delay min-delay)))))))

(defn randomize-strategy
  "Returns a new strategy where all the delays have been scaled by a
  random number between [1 - rand-factor, 1 + rand-factor].
  Rand-factor must be greater than 0 and less than 1."
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
  "Limit the maximum wallclock time of the operation to `timeout` (ms)"
  [timeout retry-strategy]
  (when (and (pos? timeout) (seq retry-strategy))
    (let [[f & r] retry-strategy]
      (cons f
            (lazy-seq (max-duration (- timeout f) r))))))

(defn- sleep
  [delay]
  (Thread/sleep (long delay)))

(defn- should-add-metadata?
  "Determines whether or not awe should add the retry count to the
  result's Clojure metadata"
  [obj]
  (map? obj))

(defn- add-retry-count
  "Adds a retry count to the Clojure metadata which represents how many
   retries were necessary for the operation to complete successfully"
  [obj retry-count]
  (if (should-add-metadata? obj)
    (vary-meta obj assoc ::retry-count retry-count)
    obj))

(defn get-retry-count
  [obj]
  "Retrieves the number of retries necessary for the
  result to be calculated successfully"
  (-> obj
      meta
      ::retry-count))

(defn with-retries*
  [strategy f retry-count]
  (if-let [[res] (try
                   [(f)]
                   (catch Exception e
                     (when-not (seq strategy)
                       (throw e))))]
    (add-retry-count res retry-count)
    (let [[delay & strategy] strategy]
      (sleep delay)
      (recur strategy f (inc retry-count)))))

(defmacro with-retries
  "Try executing `body`. If `body` throws an Exception, retry
  according to the retry `strategy`.

  A retry `strategy` is a seq of delays: `with-retries` will sleep the
  duration of the delay (in ms) between each retry. The total number
  of tries is the number of elements in the `strategy` plus one. A
  simple retry stategy would be: [100 100 100 100] which results in
  the operation being retried four times (for a total of five tries)
  with 100ms sleeps in between tries. Note: that infinite strategies
  are supported, but maybe not encouraged...

  Strategies can be built with the provided builder fns, eg
  `linear-strategy`, but you can also create any custom seq of
  delays that suits your use case."
  [strategy & body]
  `(with-retries* ~strategy (fn [] ~@body) 0))
