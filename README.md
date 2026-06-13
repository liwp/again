# Again

[![CI](https://github.com/liwp/again/actions/workflows/ci.yml/badge.svg)](https://github.com/liwp/again/actions/workflows/ci.yml)

A Clojure library for making operations resilient: **retrying** transient failures
(`with-retries`) and **short-circuiting** persistently-failing dependencies
(`with-circuit-breaker`).

> **New in 2.0:** circuit breakers join retries as a first-class tool —
> see [Circuit breakers](#circuit-breakers).

## Clojars

```clj
[listora/again "2.0.0"]
```

With `deps.edn`:

```clj
listora/again {:mvn/version "2.0.0"}
```

Requires Clojure 1.8 or later.

## Upgrading to 2.0

2.0 adds circuit breakers (see [Circuit breakers](#circuit-breakers)) alongside the
existing retry API — existing `with-retries` code keeps working. The one breaking
change is in the retry callback: `:again.core/status` can now also be `:interrupted`
(when an `InterruptedException` stops retrying), in addition to `:success`, `:retry`,
and `:failure`. If your callback dispatches exhaustively on the status without a
`:default` case (e.g. a `defmulti` or `case` over `:again.core/status`), add an
`:interrupted` branch.

## Development

Run the test suite with the Clojure CLI:

```sh
clojure -X:test
```

Check (or fix) formatting with cljfmt:

```sh
clojure -M:fmt/check
clojure -M:fmt/fix
```

Release coordinate: `listora/again`. Releases are published to Clojars.

### Releasing

1. **Update version references** — bump the version in `build.clj` (the
   `version` var), `README.md` (the Clojars snippet), and the `deps.edn`
   comment.

2. **Update `CHANGELOG.md`** — rename the `[Unreleased]` section to the new
   version with today's date, add a fresh empty `[Unreleased]` section at the
   top, and update the comparison links at the bottom.

3. **Commit and tag:**
   ```sh
   git commit -am "Release x.y.z"
   git tag vx.y.z
   git push origin main --tags
   ```

4. **Deploy to Clojars** — set `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` (use
   a deploy token, not your account password), then run:
   ```sh
   clojure -T:build jar
   clojure -T:build deploy
   ```

---

## Usage

Require the library:

```clj
(require '[again.core :as again])
```

*Again* provides two composable resilience tools:

- **[Retries](#retries)** — retry an operation while it keeps throwing, following a
  delay strategy (`with-retries`). For *transient* failures that are likely to pass
  on a retry.
- **[Circuit breakers](#circuit-breakers)** — stop calling a dependency that has been
  failing consistently and fail fast until it recovers (`with-circuit-breaker`). For
  *persistent* failures.

The two are independent and **compose by nesting** — and the nesting order matters,
because it decides what the breaker counts as a single failure:

- **Breaker innermost** (retries on the outside): the breaker sees *every individual
  attempt*, so a burst of retries against a sick dependency trips it quickly. This is
  the recommended default.
- **Breaker outermost** (retries on the inside): the breaker sees *one outcome per
  fully-exhausted retry block*, so it counts whole logical operations, not attempts.

A typical setup is breaker-innermost, paired with a retry callback that returns
`::again/fail` on a circuit-open exception, so the retry loop stops the moment the
breaker opens instead of sleeping through its remaining delays:

```clj
(def breaker
  (again/circuit-breaker (again/consecutive-failures 5) {::again/reset-timeout 30000}))

(again/with-retries
  {::again/strategy (again/max-retries 10 (again/constant-strategy 100))
   ;; once the breaker is open, stop retrying instead of waiting out the delays
   ::again/callback (fn [{e ::again/exception}]
                      (when (and e (again/circuit-open? e)) ::again/fail))}
  (again/with-circuit-breaker breaker   ; ← innermost: the breaker counts every attempt
    (call-some-service)))
```

Swap the two `with-…` forms and the breaker instead counts one failure per
exhausted retry block. See [Retries](#retries) and [Circuit breakers](#circuit-breakers)
for each tool on its own.

## Retries

*Again* provides a very simple (too simple?) API for retrying an operation:
given a retry strategy and an operation, the operation will be retried according
to the provided strategy as long as it throws an exception. Only an exception is
considered a failure - the library does not consider the return value of the
operation.

A retry strategy is just a sequence of integers that represent a delay in
milliseconds before the operation is attempted again. Once the sequence runs
out, `with-retries` will re-throw the last exception.

A fundamental design goal of the library is to allow an existing form to be
wrapped in `with-retries` without any other code changes in order to enable
retries of that form.

Eg:
```clj
(original-form …)
```

Becomes:
```clj
(with-retries …
  (original-form …))
```

A note on terminology: an *attempt* is an execution of the wrapped form, whereas
a *retry* is any subsequent execution of the wrapped form after the initial
failed attempt. Documentation in the earlier versions of this library used
*retry* everywhere, but we've tried to make a cleaner distinction between the
two terms since then.

### Basic use case:

```clj
(require '[again.core :as again])

(again/with-retries
  [100 1000 10000]
  (my-operation …))
```

The above will attempt executing `my-operation` four times, with `100ms`,
`1000ms` and `10000ms` delays between each attempt.

The library provides a numbers of functions for generating and manipulating
retry strategies. Most of the provided generators return strategies of infinite
delay sequences. The infinite strategies can be restricted with the manipulator
functions.

### Advanced use case:

The advanced form allows you to pass in other options than just the retry
strategy.

```clj
(require '[again.core :as again])

(defmulti log-attempt ::again/status)
(defmethod log-attempt :retry [s]
  (swap! (::again/user-context s) assoc :retried? true)
  (println "RETRY" s))
(defmethod log-attempt :success [s]
  (if (-> s ::again/user-context deref :retried?)
    (println "SUCCESS after" (::again/attempts s) "attempts" s)
    (println "SUCCESS on first attempt" s)))
(defmethod log-attempt :failure [s]
  (println "FAILURE" s))

(again/with-retries
  {::again/callback log-attempt
   ::again/strategy [100 1000 10000]
   ::again/user-context (atom {})}
  (my-operation …))
```

The above example is contrived (there's no need to set `:retried?` in the user
context since the `:success` callback could just check if `::again/attempts` is
greater than `1`), but it tries to show that:

- instead of a sequence of delays, `with-retries` also accepts a map as its
  first argument
- the `:again.core/strategy` key is used to pass in the delay strategy
- the `:again.core/callback` key can be used to specify a function that will get
  called after each attempt
- the `:again.core/user-context` key can be used to specify a user-defined
  context object that will get passed to the callback function

The callback function and the context object allows (hopefully!) for arbitrary
monitoring implementations where the results of each attempt can be eg logged to
a monitoring system.

The callback is called with a map as its only argument:

```clj
{
  ;; the number of form executions - a positive integer
  :again.core/attempts …
  ;; the exception that was thrown when the execution failed (not present
  ;; in the `:success` case)
  :again.core/exception …
  ;; the sum of all delays thus far (in milliseconds)
  :again.core/slept …
  ;; the result of the previous execution - `:success`, `:retry`, `:failure`,
  ;; or `:interrupted`
  :again.core/status …
  ;; the `:again.core/user-context` value from the map passed to `with-retries`
  :again.core/user-context …
}
```

The callback can also return the `:again.core/fail` keyword to ignore the rest
of the retry strategy and throw the current exception from `with-retries` (That
is, it provides a mechanism for early termination). For example, the callback
could check the exception's `ex-data` and decide to fail the operation:

```clj
(again/with-retries
  {::again/callback #(when (-> % ::again/exception ex-data :fail?) ::again/fail)
   ::again/strategy [100 1000 10000]}
  (my-operation …))
```


### Generators:

* `additive-strategy` - an infinite sequence of incrementally increasing delays
* `constant-strategy` - an infinite sequence of the given delay
* `immediate-strategy` - an infinite sequence of `0ms` delays
* `multiplicative-strategy` - an infinite sequence of exponentially increasing delays
* `stop-strategy` - `nil`, ie no retries

### Manipulators:

* `clamp-delay` - limit the delay to a given number
* `max-delay` - truncate the sequence once the delay between two attempts exceeds the given number
* `max-duration` - truncate the sequence once the sum of all past delays exceeds the given number (delay budget only; does not account for execution time)
* `max-retries` - truncate the sequence after the given number of retries
* `max-wall-clock-duration` - truncate the sequence once wall-clock elapsed time since the first attempt exceeds the given number (includes execution time, unlike `max-duration`)
* `randomize-strategy` - scale each delay with a new random number

### Exponential backoff example:

The generators and manipulators can be combined to create a desired retry
strategy. Eg an exponential backoff retry strategy with an initial delay of
`500ms` and a multiplier of `1.5`, limited to either `10` retries or a maximum
combined delay of `10s` can be generated as follows:

```clj
(def exponential-backoff-strategy
  (again/max-duration
    10000
    (again/max-retries
      10
      (again/randomize-strategy
        0.5
        (again/multiplicative-strategy 500 1.5)))))
```

To limit retries by wall-clock time (including actual execution time, not just
accumulated delays), use `max-wall-clock-duration` as the outermost wrapper:

```clj
(def exponential-backoff-strategy-with-timeout
  (again/max-wall-clock-duration
    30000
    (again/max-retries
      10
      (again/multiplicative-strategy 500 1.5))))
```

This stops retrying once 30 seconds have elapsed since the first attempt,
regardless of how long individual attempts take. Note that
`max-wall-clock-duration` returns an options map rather than a seq, so it must
be the outermost manipulator.

We can also prepend a `0` to the strategy in order to execute the
first retry immediately:

```clj
(def exponential-backoff-strategy-with-immediate-retry
  (cons 0 exponential-backoff-strategy))
```

## Circuit breakers

A circuit breaker short-circuits calls to a dependency that has been failing
consistently, giving it time to recover and failing fast in the meantime. Construct
one breaker and share it across callers:

```clj
(require '[again.core :as again])

(def breaker
  (again/circuit-breaker
    (again/consecutive-failures 5)        ; trip after 5 consecutive failures
    {:again.core/reset-timeout 30000      ; stay open 30s before a half-open probe
     :again.core/on-event
     (fn [{ev :again.core/event from :again.core/from to :again.core/to}]
       (when (= ev :state-change)
         (println "breaker" from "->" to)))}))

(again/with-circuit-breaker breaker
  (call-some-service))
```

Any exception the body throws counts as a failure, except `InterruptedException` —
that is rethrown (with the interrupt flag restored) and is **not** counted. Once the
breaker has been open for `::reset-timeout` milliseconds (default `60000`, also
available as `again/default-reset-timeout`), it admits a single *half-open* probe: if
the probe succeeds the breaker closes, if it fails the breaker re-opens for another
timeout. While the breaker is open — or while a half-open probe is in flight —
`with-circuit-breaker` short-circuits, throwing instead of running the body.
`(again/circuit-open? e)` recognises that exception, and
`(again/circuit-state breaker)` returns `:closed`, `:open`, or `:half-open`.

**Monitoring.** The `:again.core/on-event` callback (used above) is the observability
hook. It fires after each notable event with a map whose `:again.core/event` is
`:success`, `:failure`, `:short-circuit`, or `:state-change`; a `:state-change` also
carries `:again.core/from`/`:again.core/to`, a `:failure` carries the
`:again.core/exception`, and any configured `:again.core/user-context` rides along on
every event. The breaker just emits these — feed them into your own logging or metrics
(e.g. counting `:short-circuit` events shows how much load the breaker is shedding).

**Custom trip behaviour.** `consecutive-failures` is the built-in policy, but *when a
closed breaker trips* is pluggable: implement the `BreakerPolicy` protocol over an
immutable value and pass it to `circuit-breaker` in place of `consecutive-failures`.
Its three methods are:

- `(observe [policy outcome now])` — return an updated policy for a `:success` or
  `:failure` outcome
- `(tripped? [policy now])` — whether the breaker should open
- `(reset [policy])` — clear accumulated state when the breaker re-closes

That's enough to plug in, say, a rolling-window or failure-rate policy. The
open/half-open/closed state machine (including the single half-open probe) is
unchanged — the policy only decides when a *closed* breaker trips.

**API.**

* `circuit-breaker` — construct a breaker from a `BreakerPolicy` and an options map
  (`::reset-timeout`, `::on-event`, `::user-context`)
* `with-circuit-breaker` — run a body through a breaker; short-circuits when open
* `consecutive-failures` — the built-in `BreakerPolicy` (trip after N consecutive failures)
* `circuit-open?` — whether an exception is the breaker's short-circuit signal
* `circuit-state` — a breaker's current state: `:closed`, `:open`, or `:half-open`
* `BreakerPolicy` — protocol for custom trip behaviour (`observe`/`tripped?`/`reset`)

To combine a breaker with retries, see [Usage](#usage) — the nesting order matters,
and breaker-innermost (so the breaker counts every attempt) is the usual choice.

## License

Copyright © 2014–2026 Listora, Lauri Pesonen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
