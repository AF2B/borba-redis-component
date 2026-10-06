(ns ^:integration borba.redis.integration-test
  "Runs against a real Redis, which the pipeline provides and which a developer
   starts with

     docker run --rm -d --name borba-redis-it -p 127.0.0.1:56379:6379 \\
       redis:8.2-alpine

   and points the tests at with REDIS_HOST and REDIS_PORT (127.0.0.1 and
   56379)."
  (:require
   [borba.redis :as redis]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [integrant.core :as ig]
   [taoensso.carmine :as car]))

(set! *warn-on-reflection* true)

(defn- environment
  [variable]
  (or (System/getenv variable)
      (throw (ex-info (str "set " variable " to run the integration tests")
                      {:variable variable}))))

(defn- options
  "The options of a connection to the Redis of the tests, with more options."
  [more]
  (merge {:host (environment "REDIS_HOST")
          :port (parse-long (environment "REDIS_PORT"))}
         more))

(def ^:private prefix (str "borba-it:" (random-uuid) ":"))

(defn- k
  "Returns a key of this run, which the fixture deletes."
  [suffix]
  (str prefix suffix))

(def ^:dynamic *conn*
  "The connection of the tests, bound for the run of the namespace."
  nil)

(defn- with-redis
  "Starts the connection, and deletes the keys of the run after."
  [run-tests]
  (let [system (ig/init {:components/redis (options {})})
        conn   (:components/redis system)]
    (try
      (binding [*conn* conn]
        (run-tests))
      (finally
        (let [keys-left (redis/wcar* conn (car/keys (str prefix "*")))]
          (when (seq keys-left)
            (apply redis/del! conn keys-left)))
        (ig/halt! system)))))

(use-fixtures :once with-redis)

(deftest strings-test
  (testing "sets and gets a string, as it is"
    (is (true? (redis/set! *conn* (k "s") "hello")))
    (is (= "hello" (redis/get! *conn* (k "s"))))
    (is (= "string" (redis/wcar* *conn* (car/type (k "s"))))))

  (testing "gets nil for a key that is not there"
    (is (nil? (redis/get! *conn* (k "missing")))))

  (testing "gets some keys at once, with a nil for each that is not there"
    (redis/set! *conn* (k "a") "1")
    (redis/set! *conn* (k "b") "2")
    (is (= ["1" nil "2"]
           (redis/mget! *conn* (k "a") (k "nothing") (k "b")))))

  (testing "keeps what is not a string in a form that Clojure reads back"
    (redis/set! *conn* (k "m") {:a 1 :b [2 3]})
    (is (= {:a 1 :b [2 3]} (redis/get! *conn* (k "m")))))

  (testing "knows whether a key exists, and deletes it"
    (redis/set! *conn* (k "e") "x")
    (is (true? (redis/exists? *conn* (k "e"))))
    (is (= 1 (redis/del! *conn* (k "e"))))
    (is (false? (redis/exists? *conn* (k "e"))))
    (is (= 0 (redis/del! *conn* (k "e"))))))

(deftest set-options-test
  (testing "sets only if the key is absent, with :nx?"
    (is (true? (redis/set! *conn* (k "nx") "first" {:nx? true})))
    (is (false? (redis/set! *conn* (k "nx") "second" {:nx? true})))
    (is (= "first" (redis/get! *conn* (k "nx")))))

  (testing "sets only if the key is there, with :xx?"
    (is (false? (redis/set! *conn* (k "xx") "x" {:xx? true})))
    (redis/set! *conn* (k "xx") "old")
    (is (true? (redis/set! *conn* (k "xx") "new" {:xx? true})))
    (is (= "new" (redis/get! *conn* (k "xx")))))

  (testing "expires the key, with :ttl-seconds"
    (redis/set! *conn* (k "ttl") "x" {:ttl-seconds 100})
    (is (<= 90 (redis/ttl! *conn* (k "ttl")) 100)))

  (testing "combines them"
    (is (true? (redis/set! *conn* (k "both") "x" {:ttl-seconds 100
                                                  :nx?         true})))
    (is (false? (redis/set! *conn* (k "both") "y" {:ttl-seconds 100
                                                   :nx?         true})))
    (is (<= 90 (redis/ttl! *conn* (k "both")) 100))))

(deftest expiry-test
  (testing "has -1 without an expiry and -2 without a key"
    (redis/set! *conn* (k "forever") "x")
    (is (= -1 (redis/ttl! *conn* (k "forever"))))
    (is (= -2 (redis/ttl! *conn* (k "nowhere")))))

  (testing "sets the expiry of a key that is there, and says so"
    (is (true? (redis/expire! *conn* (k "forever") 50)))
    (is (<= 40 (redis/ttl! *conn* (k "forever")) 50))
    (is (false? (redis/expire! *conn* (k "nowhere") 50)))))

(deftest counters-test
  (testing "adds one, and an amount, in one step, from zero"
    (is (= 1 (redis/incr! *conn* (k "n"))))
    (is (= 2 (redis/incr! *conn* (k "n"))))
    (is (= 12 (redis/incrby! *conn* (k "n") 10)))
    (is (= 7 (redis/incrby! *conn* (k "n") -5)))))

(deftest hashes-test
  (testing "sets and gets the fields of a hash"
    (is (= 2 (redis/hset! *conn* (k "h") {"name" "Ana" "email" "a@b.c"})))
    (is (= "Ana" (redis/hget! *conn* (k "h") "name")))
    (is (= {"name" "Ana" "email" "a@b.c"} (redis/hgetall! *conn* (k "h")))))

  (testing "removes fields"
    (is (= 1 (redis/hdel! *conn* (k "h") "email")))
    (is (= {"name" "Ana"} (redis/hgetall! *conn* (k "h")))))

  (testing "has nil for a hash that is not there, or a field"
    (is (nil? (redis/hgetall! *conn* (k "no-hash"))))
    (is (nil? (redis/hget! *conn* (k "h") "nothing")))))

(deftest lists-test
  (testing "pushes to the tail and to the head, and reads a range"
    (is (= 3 (redis/rpush! *conn* (k "l") "b" "c" "d")))
    (is (= 4 (redis/lpush! *conn* (k "l") "a")))
    (is (= ["a" "b" "c" "d"] (redis/lrange! *conn* (k "l") 0 -1)))
    (is (= ["b" "c"] (redis/lrange! *conn* (k "l") 1 2)))
    (is (= 4 (redis/llen! *conn* (k "l")))))

  (testing "has no elements and no length for a list that is not there"
    (is (= [] (redis/lrange! *conn* (k "no-list") 0 -1)))
    (is (= 0 (redis/llen! *conn* (k "no-list"))))))

(deftest pipeline-test
  (testing "runs several commands in one round trip"
    (is (= ["OK" "v"]
           (redis/wcar* *conn*
                        (car/set (k "p") "v")
                        (car/get (k "p")))))))

(deftest failures-test
  (testing "a command on a key of another type is a wrong type"
    (redis/set! *conn* (k "string") "x")
    (is (= {:error :wrong-type :prefix :wrongtype}
           (try (redis/lpush! *conn* (k "string") "y")
                nil
                (catch Exception e (redis/error-data e))))))

  (testing "an exception that is not from Redis is nil"
    (is (nil? (redis/error-data (IllegalStateException. "no"))))))

(deftest health-test
  (testing "Redis is ready"
    (is (true? (redis/ready? *conn*)))))

(deftest timeouts-test
  (testing "a reply that does not come in time is a timeout, soon"
    (let [system  (ig/init {:components/redis
                            (options {:read-timeout-ms 300})})
          conn    (:components/redis system)
          started (System/nanoTime)]
      (try
        (is (= :timeout
               (try (redis/wcar* conn (car/blpop (k "empty-list") 5))
                    nil
                    (catch Exception e (:error (redis/error-data e))))))
        (is (< (/ (- (System/nanoTime) started) 1e6) 3000))
        (finally
          (ig/halt! system))))))

(deftest lifecycle-test
  (testing "is not ready once the pool is closed"
    (let [system (ig/init {:components/redis (options {})})
          conn   (:components/redis system)]
      (is (true? (redis/ready? conn)))
      (ig/halt! system)
      (is (false? (redis/ready? conn)))))

  (testing "uses another database"
    (let [system (ig/init {:components/redis (options {:database 5})})
          conn   (:components/redis system)]
      (try
        (redis/set! conn (k "db5") "here")
        (is (= "here" (redis/get! conn (k "db5"))))
        (is (nil? (redis/get! *conn* (k "db5"))))
        (finally
          (redis/del! conn (k "db5"))
          (ig/halt! system)))))

  (testing "a Redis that cannot be reached fails the start naming only where"
    (let [thrown (try (ig/init {:components/redis
                                {:host                  "127.0.0.1"
                                 :port                  1
                                 :password              "hunter2"
                                 :connection-timeout-ms 300}})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
          cause  (ex-cause thrown)]
      (is (= {:error   :borba.redis/cannot-connect
              :address "127.0.0.1:1/0"}
             (ex-data cause)))
      (is (not (re-find #"hunter2" (ex-message cause))))
      (is (= {:error :connection-failure}
             (redis/error-data (ex-cause cause)))))))
