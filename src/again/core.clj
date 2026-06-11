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

(defn- current-time-ms [] (System/currentTimeMillis))

(defn max-wall-clock-duration
  "Stop retrying once wall-clock time since the first attempt exceeds
  timeout-ms. Unlike max-duration, this includes actual execution time,
  not just accumulated delays.

  Returns an options map for use with with-retries. Because it returns a
  map rather than a seq, it must be the outermost manipulator — other
  manipulators (max-retries, clamp-delay, etc.) should be applied to the
  strategy before passing it here.

  The returned map can be merged with other with-retries options such as
  ::callback and ::user-context, or ::wall-clock-timeout can be set
  directly in the options map passed to with-retries instead of using
  this function."
  [timeout-ms retry-strategy]
  {:pre [(>= timeout-ms 0)
         (not (map? retry-strategy))]}
  {::strategy retry-strategy
   ::wall-clock-timeout timeout-ms})

(defn- sleep
  [delay]
  (try
    (Thread/sleep (long delay))
    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      (throw e))))

(defn- build-options
  "Turn a strategy-sequence to an options-map (if it's not a map already), and
  define a default callback function."
  [strategy-or-options]
  (let [default-options {::callback (constantly nil)}
        options (if (map? strategy-or-options)
                  strategy-or-options
                  {::strategy strategy-or-options})]
    (merge default-options options)))

(defn with-retries*
  [strategy-or-options f]
  (let [{callback ::callback
         delays ::strategy
         :as options}
        (build-options strategy-or-options)
        options (cond-> options
                  (::wall-clock-timeout options)
                  (assoc ::start-time (current-time-ms)))

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
                         (let [interrupted? (instance? InterruptedException e)
                               timed-out?   (when-let [timeout (::wall-clock-timeout options)]
                                              (>= (- (current-time-ms) (::start-time options))
                                                  timeout))
                               cb-result    (-> callback-state
                                                (assoc
                                                 ::exception e
                                                 ::status (cond
                                                            (and delay (not timed-out?) (not interrupted?)) :retry
                                                            interrupted? :interrupted
                                                            :else :failure))
                                                callback)
                               retry?       (and (not timed-out?) (not interrupted?) (not= cb-result ::fail))]
                           (when interrupted?
                             (.interrupt (Thread/currentThread)))
                           (if (and delay retry?)
                             (sleep delay)
                             (throw e)))
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
  infinite strategies are supported, but maybe not encouraged…

  Strategies can be built with the provided builder fns, eg `additive-strategy`,
  and modified with the provided manipulator fns, eg `clamp-delay`, but you can
  also create any custom seq of delays that suits your use case.

  Instead of a simple delay sequence, you can also pass in the following type of
  options map as the first argument to `with-retries`:

  {:again.core/callback <fn>
   :again.core/strategy <delay strategy>
   :again.core/user-context <anything, but probably an atom>
   :again.core/wall-clock-timeout <ms>}

  `:again.core/callback` is a callback function that is called after each
  attempt.

  `:again.core/strategy` is the sequence of delays (ie retry strategy).

  `:again.core/user-context` is an opaque value that is passed to the callback
  function as an argument.

  `:again.core/wall-clock-timeout` stops retrying once wall-clock elapsed time
  since the first attempt exceeds this value (ms). Unlike the delay-summing
  `max-duration` manipulator, this accounts for actual execution time. Setting
  this key directly is equivalent to using `max-wall-clock-duration`.

  The callback function is called with the following type of map as its only
  argument:

  {:again.core/attempts <the number of attempts thus far>
   :again.core/exception <the exception thrown by body>
   :again.core/slept <the sum of all delays thus far>
   :again.core/status <the result of the last attempt: :success, :retry, :failure, or :interrupted
   :again.core/user-context <the user context from the options map>}

  The callback function can return `:again.core/fail` to instruct `with-retries`
  to ignore the rest of the retry strategy and rethrow the previous
  exception (ie return early)."
  [strategy-or-options & body]
  `(with-retries* ~strategy-or-options (fn [] ~@body)))

(defprotocol BreakerPolicy
  "Decides when a closed circuit breaker should trip open, from observed call
  outcomes. Implementations are immutable values; the breaker stores the current
  policy in its state and swaps in the next one after each recorded outcome. The
  state machine itself (open/half-open/closed) lives in the breaker runtime, not
  here. This is the extension seam for alternative trip policies (e.g. a rolling
  window) — implement the three methods over your own immutable value."
  (observe [policy outcome now]
    "Return an updated policy incorporating a call `outcome` (`:success` or
    `:failure`) observed at `now` (epoch ms).")
  (tripped? [policy now]
    "True if the breaker should open, given the outcomes observed so far.")
  (reset [policy]
    "Return the policy with its accumulated outcome state cleared but its
    configuration preserved. Called when the breaker (re)closes."))

(defrecord ConsecutiveFailures [threshold failures]
  BreakerPolicy
  (observe [this outcome _now]
    (if (= outcome :failure)
      (update this :failures inc)
      (assoc this :failures 0)))
  (tripped? [_this _now]
    (>= failures threshold))
  (reset [this]
    (assoc this :failures 0)))

(defn consecutive-failures
  "Returns a `BreakerPolicy` that trips after `threshold` consecutive failures.
  A single success resets the count."
  [threshold]
  {:pre [(pos? threshold)]}
  (->ConsecutiveFailures threshold 0))

(def ^:private default-reset-timeout
  "Default ms a breaker stays open before admitting a half-open probe."
  60000)

(defn circuit-breaker
  "Returns a stateful circuit breaker driven by `policy` (a `BreakerPolicy`).
  The breaker is shared across callers and threads; construct it once.

  `options` keys (all namespaced under `again.core`):

    `::reset-timeout`  ms to stay open before admitting a half-open probe
                       (default 60000)
    `::on-event`       fn called with an event map after each notable event
                       (see `with-circuit-breaker`); defaults to a no-op
    `::user-context`   opaque value included in every `::on-event` map"
  ([policy] (circuit-breaker policy {}))
  ([policy options]
   {:pre [(satisfies? BreakerPolicy policy)]}
   (cond-> {::reset-timeout (get options ::reset-timeout default-reset-timeout)
            ::on-event (get options ::on-event (constantly nil))
            ::state (atom {::circuit :closed ::policy policy ::opened-at nil})}
     (contains? options ::user-context)
     (assoc ::user-context (::user-context options)))))

(defn circuit-state
  "Returns the current state of `breaker`: `:closed`, `:open`, or `:half-open`."
  [breaker]
  (::circuit @(::state breaker)))

(defn- decide-allow
  "Pure step: given the current breaker `state`, decide whether a call may
  proceed. Returns {::permitted? bool ::next <state> ::transition [from to]|nil}.
  (Closed-only for now; the open/half-open logic is added later.)"
  [state _now _reset-timeout]
  (if (= (::circuit state) :closed)
    {::permitted? true ::next state ::transition nil}
    {::permitted? false ::next state ::transition nil}))

(defn- decide-record
  "Pure step: given the current breaker `state` and a call `outcome`, decide the
  next state. Returns {::next <state> ::transition [from to]|nil}."
  [state outcome now]
  (if (= (::circuit state) :closed)
    (let [policy' (observe (::policy state) outcome now)]
      (if (tripped? policy' now)
        {::next       (assoc state ::circuit :open ::policy policy' ::opened-at now)
         ::transition [:closed :open]}
        {::next       (assoc state ::policy policy')
         ::transition nil}))
    {::next state ::transition nil}))

(defn- allow!
  "Atomically apply `decide-allow` to the breaker, retrying on contention.
  Returns {::permitted? bool ::transition [from to]|nil}."
  [breaker]
  (let [a             (::state breaker)
        reset-timeout (::reset-timeout breaker)]
    (loop []
      (let [s        @a
            decision (decide-allow s (current-time-ms) reset-timeout)
            next     (::next decision)]
        (if (or (identical? next s) (compare-and-set! a s next))
          (dissoc decision ::next)
          (recur))))))

(defn- record!
  "Atomically apply `decide-record` to the breaker, retrying on contention.
  Returns {::transition [from to]|nil}."
  [breaker outcome]
  (let [a (::state breaker)]
    (loop []
      (let [s        @a
            decision (decide-record s outcome (current-time-ms))
            next     (::next decision)]
        (if (or (identical? next s) (compare-and-set! a s next))
          (dissoc decision ::next)
          (recur))))))

(defn with-circuit-breaker*
  "Functional core of `with-circuit-breaker`: runs `f` through `breaker`."
  [breaker f]
  (let [{permitted? ::permitted?} (allow! breaker)]
    (if permitted?
      (try
        (let [result (f)]
          (record! breaker :success)
          result)
        (catch Exception e
          (record! breaker :failure)
          (throw e)))
      (throw (ex-info "again.core circuit breaker is open" {::circuit-open true})))))

(defmacro with-circuit-breaker
  "Run `body` through `breaker`. While the breaker is closed (or admitting a
  half-open probe) the body executes and its success or failure is recorded
  against the breaker. While the breaker is open the body is NOT executed; a
  circuit-open exception (recognised by `circuit-open?`) is thrown instead.

  Any thrown `Exception` counts as a failure: it is recorded against the breaker
  and then rethrown.

  Compose with `with-retries` by nesting, breaker innermost, so the breaker sees
  every attempt:

      (with-retries strategy
        (with-circuit-breaker breaker
          (do-call)))"
  [breaker & body]
  `(with-circuit-breaker* ~breaker (fn [] ~@body)))

(defn circuit-open?
  "True if `e` is the exception thrown when a call is short-circuited by an open
  circuit breaker (see `with-circuit-breaker`)."
  [e]
  (boolean (::circuit-open (ex-data e))))
