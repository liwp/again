# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `max-wall-clock-duration` manipulator: stops retrying once wall-clock elapsed
  time since the first attempt exceeds a given timeout. Unlike `max-duration`,
  this accounts for actual execution time, not just accumulated delays.
- Circuit breaker support: `circuit-breaker`, the `with-circuit-breaker` macro, the
  `BreakerPolicy` protocol with the built-in `consecutive-failures` policy,
  `circuit-open?`, and `circuit-state`. Breakers have closed/open/half-open states
  with a configurable failure threshold and `::reset-timeout`, emit `::on-event`
  notifications (`:success`, `:failure`, `:short-circuit`, `:state-change`), and
  compose with `with-retries` by nesting (breaker innermost). `BreakerPolicy` is an
  extension seam for alternative trip policies (e.g. a rolling window).

### Added
- Callback `::status` gains a new `:interrupted` value when retrying is stopped
  by an `InterruptedException`, distinguishing it from `:failure` (retries
  exhausted). **Note:** callers that dispatch exhaustively on `::status` without
  a `:default` arm will need to add an `:interrupted` case.

### Fixed
- `InterruptedException` now stops retrying immediately; the thread interrupt
  flag is restored before rethrowing so callers can detect the interruption.

### Changed
- Build migrated from Leiningen to Clojure CLI (`deps.edn`).
- `org.clojure/clojure` updated to 1.12.5.

## [1.0.0] - 2019-04-22

Initial stable release.

[Unreleased]: https://github.com/liwp/again/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/liwp/again/releases/tag/v1.0.0
