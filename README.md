# Again

[![Build Status](https://travis-ci.org/liwp/again.png?branch=master)](https://travis-ci.org/liwp/again)

A Clojure library for retrying an operation based on a retry strategy.

## Clojars

```clj
[listora/again "1.0.0"]
```

---

## Usage

Require the library:

```clj
(require '[again.core :as again])
```

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
  ;; the result of the previous execution - `:success`, `:failure`, or
  ;; `:retry`
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
* `max-duration` - truncate the sequence once the sum of all past delays exceeds the given number
* `max-retries` - truncate the sequence after the given number of retries
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

We can also prepend a `0` to the strategy in order to execute the
first retry immediately:

```clj
(def exponential-backoff-strategy-with-immediate-retry
  (cons 0 exponential-backoff-strategy))
```

## License

Copyright © 2014–2017 Listora, Lauri Pesonen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
