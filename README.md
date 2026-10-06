# borba-redis-component

[![CI](https://github.com/AF2B/borba-redis-component/actions/workflows/ci.yml/badge.svg)](https://github.com/AF2B/borba-redis-component/actions/workflows/ci.yml)

Redis for a Borba service: a pool of connections ([Carmine](https://github.com/taoensso/carmine)) as an
[Integrant](https://github.com/weavejester/integrant) component that authenticates, can use TLS and waits for no more than it
is told, and the common commands as functions. A failure comes back as data.

## Install

```clojure
io.github.af2b/borba-redis-component
{:git/url "https://github.com/AF2B/borba-redis-component"
 :git/tag "v2.0.0"
 :git/sha "<the commit of the tag, printed in the release notes>"}
```

It depends on Clojure, Integrant, `tools.logging` and Carmine. It does not choose a logging backend.

## Use

```clojure
{:service/namespaces [borba.redis]

 :ig/system
 {:components/redis
  {:host     #or [#env REDIS_HOST "localhost"]
   :port     #long #or [#env REDIS_PORT 6379]
   :password #env REDIS_PASSWORD
   :ssl?     true}}}
```

The value of the component is the connection that every function takes first: a map of the `:pool`, which the component owns and
closes when it halts, and the `:spec` of the connection.

```clojure
(require '[borba.redis :as redis])

(redis/set! conn "session:abc" "token" {:ttl-seconds 3600})   ;; => true
(redis/get! conn "session:abc")                                ;; => "token"
(redis/ttl! conn "session:abc")                                ;; => 3600, then counting down
(redis/exists? conn "session:abc")                             ;; => true
(redis/del! conn "session:abc")                                ;; => 1, the number of keys deleted

(redis/incr! conn "visits")                                    ;; => 1
(redis/incrby! conn "visits" 10)                               ;; => 11

(redis/hset! conn "user:1" {"name" "Ana" "email" "ana@example.com"})  ;; => 2
(redis/hgetall! conn "user:1")                                 ;; => {"name" "Ana", "email" "ana@example.com"}

(redis/rpush! conn "queue" "b" "c")                            ;; => 2
(redis/lpush! conn "queue" "a")                                ;; => 3
(redis/lrange! conn "queue" 0 -1)                              ;; => ["a" "b" "c"]
```

`set!` returns whether the key was set: `false` when `:nx?` (only if the key is absent) or `:xx?` (only if it is there) kept it from
being. `:ttl-seconds` expires the key, and an option that Redis would refuse, such as `:nx?` with `:xx?` or a `:ttl-seconds` that is not
a positive integer, fails before anything is sent.

For a command that is not here, `wcar*` runs the commands of Carmine as a pipeline, in one round trip:

```clojure
(require '[taoensso.carmine :as car])

(redis/wcar* conn
             (car/set "k" "v")
             (car/get "k"))
;; => ["OK" "v"]
```

| Option | What it is | Default |
|---|---|---|
| `:host` | Where Redis is | `"localhost"` |
| `:port` | The port | `6379` |
| `:username`, `:password` | The credentials, for Redis 6 and later | none |
| `:database` | The number of the database | `0` |
| `:ssl?` | Whether to use TLS | `false` |
| `:connection-timeout-ms` | How long to wait to connect | `2000` |
| `:read-timeout-ms` | How long to wait for the reply to a command | `5000` |
| `:max-connections` | The most connections in the pool | `16` |
| `:min-idle` | The fewest idle connections the pool keeps | `0` |

From the environment the port is a string: `#long #or [#env REDIS_PORT 6379]` makes it the integer that is needed, and a port that is
not one fails the start saying what it got. The password is never in a message or a log.

## What is stored

A string is stored as it is, which is what another program reads. Anything else, a number, a map, a vector, is stored with
[Nippy](https://github.com/taoensso/nippy), which only Clojure reads back:

```clojure
(redis/set! conn "m" {:a 1 :b [2 3]})
(redis/get! conn "m")                    ;; => {:a 1, :b [2 3]}   (stored as a Redis string of Nippy bytes)
```

Nippy must not read what something that is not trusted can write to Redis. Keep what crosses that line as strings, JSON for
instance, and keep Nippy for the values that only this service writes and reads.

## Limits

Every wait has one. Carmine, by itself, waits four seconds to connect and **for a reply for as long as it takes**, so a Redis that
stopped answering keeps a thread of the service for ever. Here a connection waits two seconds, and a command is an error after
five. A blocking command such as `BLPOP` is told to wait for less than that, or the component is given a longer `:read-timeout-ms`:

```clojure
(try (redis/wcar* conn (car/blpop "empty-list" 5))   ;; with :read-timeout-ms 300
     (catch Exception e (redis/error-data e)))
;; => {:error :timeout}
```

**A Redis that cannot be reached fails the start.** The component sends a `PING` when it starts, and a failure says where it tried
and nothing else:

```clojure
(ig/init {:components/redis {:host "127.0.0.1" :port 1 :password "hunter2"}})
;; throws the ExceptionInfo of Integrant, whose cause (ex-cause) is
;;   "the connection to Redis cannot start on 127.0.0.1:1/0"
;;   {:error :borba.redis/cannot-connect, :address "127.0.0.1:1/0"}
```

`ready?` is for a readiness check: true when Redis answers a `PING`, and false when it does not, when it cannot be reached or when
the pool is closed. It never throws.

## Failures as data

A failure is the exception Carmine throws. `error-data` turns it into the convention of `borba.railway`: a map with an `:error`
keyword to match on.

```clojure
(try (redis/lpush! conn "a-string-key" "x")
     (catch Exception e (redis/error-data e)))
;; => {:error :wrong-type, :prefix :wrongtype}
```

| `:error` | When |
|---|---|
| `:wrong-type` | A command on a key that holds another type of value |
| `:authentication-failed` | The password is wrong (`:wrongpass`) or there is none (`:noauth`) |
| `:permission-denied` | The user may not run the command |
| `:read-only` | Redis is a replica that does not take writes |
| `:out-of-memory`, `:loading`, `:busy`, `:misconfigured` | Redis cannot take the command now |
| `:command-error` | Any other error reply (`ERR`), with its `:prefix` |
| `:redis-error` | A reply with a prefix that is not known, with the `:prefix` |
| `:timeout` | The connection or the reply took longer than the limit |
| `:connection-failure` | Redis could not be reached |

The map never has the message of the exception, which can name a key or a value, and never the credentials. `error-data` is nil for an
exception that is not Carmine's, so a caller can rethrow it.

## API

| Name | What it does |
|---|---|
| `get!`, `mget!`, `exists?`, `ttl!` | Read keys |
| `set!`, `del!`, `expire!`, `incr!`, `incrby!` | Write keys |
| `hset!`, `hget!`, `hgetall!`, `hdel!` | Hashes |
| `lpush!`, `rpush!`, `lrange!`, `llen!` | Lists |
| `wcar*` | Runs commands of Carmine as a pipeline |
| `ready?` | Whether Redis answers |
| `error-data` | The failure of a Carmine exception, as data |
| `borba.redis.config` | The checked options, turned into what Carmine takes |
| `borba.redis.errors` | The classification of the failures |
| `:components/redis` | The Integrant key that starts and stops the pool |

## Tests

The unit suite runs anywhere; it covers the options and the classification of the failures. The integration suite runs against a real
Redis, which the pipeline provides, and which you can start with Docker:

```bash
docker run --rm -d --name borba-redis-it -p 127.0.0.1:56379:6379 redis:8.2-alpine

REDIS_HOST=127.0.0.1 REDIS_PORT=56379 make test-integration
```

It covers every command, the options of `set!`, the other database, a reply that does not come in time, a wrong type, and a Redis
that cannot be reached. The coverage that the pipeline measures is the one of the unit suite, which is why its floor is low: the
commands are covered by the integration suite.

The authentication was also run by hand against `redis-server --requirepass`: the right password connects, a wrong one is
`:authentication-failed` with `:wrongpass`, and none is `:authentication-failed` with `:noauth`.

## Design notes

- **The pool belongs to the component.** Carmine can make a pool of its own behind the first command, which nothing closes. The
  component makes one, closes it when it halts, and an `ig/halt!` leaves no connection behind.
- **A command that waits for ever is a bug, not a feature.** The read timeout is on by default, and a blocking command is the one that
  says how long it waits.
- **Nothing is hidden about what is stored.** A string is a string, and the rest is Nippy, said plainly, because the difference is
  what decides whether another program can read the key.

## Development

```bash
make check      # lint, format, conventions, reflection, tests, coverage
make ci         # everything the pipelines enforce
```

See [CONTRIBUTING.md](CONTRIBUTING.md). The repository follows the [Borba standard](https://github.com/AF2B/borba-tooling/blob/main/docs/standard.md).

## License

[MIT](LICENSE)
