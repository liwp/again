# Again

[![Build Status](https://travis-ci.org/listora/again.png?branch=master)](https://travis-ci.org/listora/again)

A Clojure library for retrying an operation based on a retry strategy.

## Clojars:

```clj
[listora/again "0.1.0"]
```

--

## Usage

Require the library:

```clj
(require '[again.core :as again])
```

*Again* provides a very simple (too simple?) API for retrying an
operation: given a retry strategy and an operation, the operation will
be retried  based on the provided strategy if it throws an exception.

A retry strategy is just a sequence of integers that represent a delay
in milliseconds before retrying the operation. Once the sequence runs
out, `with-retries` will rethrow the last exception.

### Basic usecase:

```clj

(again/with-retries
  [100 1000 10000]
  (my-operation arg-1 arg-2))
```

The library provides a numbers of functions for generating and
manipulating retry strategies. Most of the provided strategies are
inifinite sequences. The strategies can be restricted with the
manipulator functions.

### Generators:

* `constant-strategy` - constant delays between retries
* `immediate-strategy` - 0ms delays between retries
* `additive-strategy` - incrementally increasing delays between retries
* `stop-strategy` - no retries
* `multiplicative-strategy` - exponentially inccreasing delays between retries

### Manipulators:

* `randomize-strategy` - scale each delay with a new random number
* `max-retries` - limit the number of retries to a given number
* `clamp-delay` - limit the delay to a given number
* `max-delay` - stop retrying when the delay crosses a given number
* `max-duration` - stop retrying when the combined delay crosses a given number

### Exponential backoff example:

The generators and manipulators can be combined to create a desired
retry strategy. Eg an exponential backoff retry strategy with an
initial delay of 500ms and a multiplier of 1.5, limited to either 10
retries or a maximum duration of 10 seconds can be generated as
follows:

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

Copyright © 2014 Listora

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
