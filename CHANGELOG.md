# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `:password`, `:username`, `:database` and `:ssl?`, so the component can connect to a Redis that asks for credentials, that has
  more than one database or that speaks TLS. It could only connect to a Redis with no password.
- `:connection-timeout-ms` (2 seconds) and `:read-timeout-ms` (5 seconds). Carmine waits for a reply for as long as it takes unless
  told otherwise, which keeps a thread of the service for as long as a Redis that stopped answering.
- `:max-connections` and `:min-idle`, the size of the pool.
- The options are checked when the system starts, and a failure names the option and never the password.
- The component sends a `PING` when it starts, and a Redis that cannot be reached fails the start with `::cannot-connect` and
  where it tried, where it used to log that it was connected.
- `ready?`, for a readiness check: true when Redis answers a `PING`, and false, without throwing, when it does not or the pool is
  closed.
- `error-data`, and `borba.redis.errors`, which turn a failure of Carmine into data: `:wrong-type`, `:authentication-failed`,
  `:permission-denied`, `:read-only`, `:out-of-memory`, `:timeout`, `:connection-failure` and more, never with the message.
- A test suite with an integration suite against a real Redis, run by the pipeline.

### Changed

- **Breaking:** the connection is a map of a pool that the component creates and closes on a halt, and the `:spec`. It was a map with
  an empty pool, so Carmine made one behind the first command that nothing closed.
- **Breaking:** `set!` returns whether the key was set, a boolean, where it returned `"OK"` or `nil`, and `expire!` returns whether
  the key was there. `set!` refuses `:nx?` together with `:xx?` and a `:ttl-seconds` that is not a positive integer, before sending
  them.
- `mget!` and `del!` name their variadic keys `ks`, where they hid `clojure.core/keys`.
- **Breaking:** the component logs through `tools.logging`, and no longer prints a host that could be nil. It no longer depends on a
  logging backend.
- Moves to Integrant 1.0 and Carmine 3.5.0.
- The published library is named `io.github.af2b/borba-redis-component`.

### Security

- Pins `io.airlift/aircompressor` to 2.0.3. The 2.0.2 that Nippy, and so Carmine, brings has a high advisory
  (GHSA-vx9q-rhv9-3jvg), which the dependency scan of the pipeline reported.

## [1.0.0] - 2026-03-29

First release: the `:components/redis` Integrant component, and `wcar*`, `get!`, `mget!`, `exists?`, `ttl!`, `set!`, `del!`,
`expire!`, `incr!`, `incrby!`, `hset!`, `hget!`, `hgetall!`, `hdel!`, `lpush!`, `rpush!`, `lrange!` and `llen!`.

[Unreleased]: https://github.com/AF2B/borba-redis-component/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/AF2B/borba-redis-component/releases/tag/v1.0.0
