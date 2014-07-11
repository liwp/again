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

(defn linear-strategy
  "Returns a retry strategy where, after the `initial-delay` (ms), the
  delay increases by `increment` (ms) after each retry."
  [initial-delay increment]
  {:pre [(>= initial-delay 0)
         (>= increment 0)]}
  (iterate #(+ increment %) initial-delay))

(defn stop-strategy
  "A no-retries policy"
  []
  nil)

;; TODO
(defn exponential-strategy
  []
  [])

(defn max-retries
  "Limit the number of retries to `n`."
  [n retry-strategy]
  {:pre [(>= n 0)]}
  (take n retry-strategy))

(defn max-delay
  "Clamp the maximum delay between retries to `delay` (ms)."
  [delay retry-strategy]
  (map #(min delay %) retry-strategy))

(defn max-duration
  "Limit the maximum wallclock time of the operation to `timeout` (ms)"
  [timeout retry-strategy]
  (when (pos? timeout)
    (let [[f & r] retry-strategy]
      (cons f
            (lazy-seq (max-duration (- timeout f) r))))))

(defn sleep
  "Delay execution for a given number of milliseconds."
  [delay]
  ;; TODO: what to do in CLJS? Use core.async to support both hosts?
  (Thread/sleep delay))

(defn with-retries*
  [strategy f]
  (if-let [[res] (try
                   [(f)]
                   (catch Exception e
                     (when-not (seq strategy)
                       (throw e))))]
    res
    (let [[delay & strategy] strategy]
      (sleep delay)
      (recur strategy f))))

(defmacro with-retries
  "Try executing `body`. If `body` throws an Exception, retry
  according to the retry `strategy`.

  A retry `strategy` is a seq of delays: `with-retries` will sleep the
  duration of the delay (in ms) between each retry. The total number
  of tries is the number of elements in the `strategy` plus one. A
  simple retry stategy would be: [100 100 100 100] which results in
  the operation being retried four times (for a total of five tries)
  with 100ms sleeps in between tries. Note: that infinite strategies
  are supported, but maybe not encouraged.

  Strategies can be built with the provided builder fns, eg
  `linear-strategy`, but you can also use a custom seq of delays."
  [strategy & body]
  `(with-retries* ~strategy (fn [] ~@body)))
