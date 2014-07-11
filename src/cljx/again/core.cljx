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

;; TODO: write a `with-retries` macro
#_(defn retry
  "Try applying `f` to `args`. Retry according to the retry-strategy
  if `f` throws an Exception. `retry-strategy` is a list of delays:
  `retry` will sleep the duration of the delay (in ms) between each
  retries. The total number of tries is the number of elements in
  `retry-strategy` plus one. A simple retry stategy would be: [100 100
  100 100] which results in the operation being retried four
  times (for a total of five tries) with 100ms sleeps in between
  tries."
  [retry-strategy f & args]
  (let [res (try
              {:value (apply f args)}
              (catch Exception e
                {:exception e}))]
    (cond
     ;; Return value
     (contains? res :value) (:value res)
     ;; Retry
     (seq retry-strategy)
     (let [[period & remaining-retries] retry-strategy]
       (println "Retrying in %sms..." period)
       ;; TODO: what to do in CLJS? Use core.async to support both hosts?
       (Thread/sleep period)
       (recur remaining-retries f args))
     ;; Throw the exception
     :else
     (throw (:exception res)))))
