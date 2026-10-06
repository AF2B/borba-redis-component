(ns borba.redis
  "Redis for a service: a pool of connections as an Integrant component, and the
   common commands as functions.

     :components/redis
     {:host     #or [#env REDIS_HOST \"localhost\"]
      :port     #long #or [#env REDIS_PORT 6379]
      :password #env REDIS_PASSWORD
      :ssl?     true}

   The value is the connection that every function takes first, and that
   `wcar*` takes for the commands that are not here. It is a map of the :pool,
   which the component owns and closes when it halts, and the :spec of the
   connection.

   A string is stored as it is, which is what another program reads. Anything
   else, a number, a map, a vector, is stored with Nippy, which only Clojure
   reads back, and which must not be read from a Redis that something not
   trusted writes to: keep what crosses that line as strings, JSON for
   instance.

   A failure is an exception, as Carmine throws it, and `error-data` turns it
   into data to match on."
  (:require
   [borba.redis.config :as config]
   [borba.redis.errors :as errors]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [taoensso.carmine :as car])
  (:import
   (java.io Closeable)))

(set! *warn-on-reflection* true)

(def ^:private ok-reply "OK")
(def ^:private pong-reply "PONG")

;; The component

(defmacro wcar*
  "Runs commands of Carmine on a connection, as a pipeline, and returns their
   replies:

     (redis/wcar* conn
       (car/set \"k\" \"v\")
       (car/get \"k\"))

   - conn: the connection, the value of the component
   - body: the commands, from taoensso.carmine"
  [conn & body]
  `(car/wcar ~conn ~@body))

(defn- ping
  "Asks Redis for a PONG, and returns whether it answered."
  [conn]
  (= pong-reply (wcar* conn (car/ping))))

(defmethod ig/init-key :components/redis
  [_ options]
  (let [{:keys [spec pool address]} (config/connection-options options)
        connection-pool             (car/connection-pool pool)
        conn                        {:pool connection-pool :spec spec}]
    (try
      (when-not (ping conn)
        (throw (ex-info "Redis did not answer a PING" {})))
      (catch Exception cause
        (.close ^Closeable connection-pool)
        (throw (ex-info (str "the connection to Redis cannot start on "
                             address)
                        {:error   ::cannot-connect
                         :address address}
                        cause))))
    (log/infof "redis pool started on %s (up to %d connections)"
               address
               (:max-total-per-key pool))
    conn))

(defmethod ig/halt-key! :components/redis
  [_ {:keys [pool]}]
  (when (instance? Closeable pool)
    (.close ^Closeable pool)
    (log/info "redis pool stopped")))

;; Reading

(defn get!
  "Returns the value at a key, or nil when there is none.
   - conn: the connection, the value of the component
   - k: the key"
  [conn k]
  (wcar* conn (car/get k)))

(defn mget!
  "Returns the values at some keys, as a vector with a nil for each key that
   has none.
   - conn: the connection, the value of the component
   - ks: the keys"
  [conn & ks]
  (wcar* conn (apply car/mget ks)))

(defn exists?
  "Returns true when the key exists.
   - conn: the connection, the value of the component
   - k: the key"
  [conn k]
  (= 1 (wcar* conn (car/exists k))))

(defn ttl!
  "Returns the seconds a key has left to live, -1 when it has no expiry and -2
   when there is no such key.
   - conn: the connection, the value of the component
   - k: the key"
  [conn k]
  (wcar* conn (car/ttl k)))

;; Writing

(defn- check-set-options
  "Fails when the options of a set cannot be sent to Redis."
  [{:keys [ttl-seconds nx? xx?]}]
  (when (and nx? xx?)
    (throw (ex-info "a set is only if the key is absent (:nx?) or only if it is
                     there (:xx?), not both"
                    {:error ::invalid-set-options})))
  (when-not (or (nil? ttl-seconds) (and (int? ttl-seconds) (pos? ttl-seconds)))
    (throw (ex-info ":ttl-seconds must be a positive integer"
                    {:error       ::invalid-set-options
                     :ttl-seconds ttl-seconds}))))

(defn- set-key!
  "Sets a key, as `set!` says, with its options already read."
  [conn
   k
   v
   {:keys [ttl-seconds nx? xx?] :as opts}]
  (check-set-options opts)
  (= ok-reply
     (wcar* conn
            (apply car/set k v
                   (concat (when ttl-seconds [:ex ttl-seconds])
                           (when nx? [:nx])
                           (when xx? [:xx]))))))

(defn set!
  "Sets a key to a value, and returns whether it was set: false when :nx? or
   :xx? kept it from being.
   - conn: the connection, the value of the component
   - k: the key
   - v: the value
   - opts: a map of :ttl-seconds, to expire the key after that many seconds;
     :nx?, to set it only if it is not there; and :xx?, to set it only if it
     is (optional)"
  ([conn
    k
    v]
   (set-key! conn k v {}))
  ([conn
    k
    v
    opts]
   (set-key! conn k v opts)))

(defn del!
  "Deletes some keys, and returns how many there were.
   - conn: the connection, the value of the component
   - ks: the keys"
  [conn & ks]
  (wcar* conn (apply car/del ks)))

(defn expire!
  "Sets a key to expire after some seconds, and returns whether the key was
   there.
   - conn: the connection, the value of the component
   - k: the key
   - ttl-seconds: the seconds the key has left to live"
  [conn
   k
   ttl-seconds]
  (= 1 (wcar* conn (car/expire k ttl-seconds))))

(defn incr!
  "Adds one to the number at a key, which is zero when there is none, in one
   step, and returns the new number.
   - conn: the connection, the value of the component
   - k: the key"
  [conn k]
  (wcar* conn (car/incr k)))

(defn incrby!
  "Adds an amount to the number at a key, in one step, and returns the new
   number.
   - conn: the connection, the value of the component
   - k: the key
   - amount: what to add"
  [conn
   k
   amount]
  (wcar* conn (car/incrby k amount)))

;; Hashes

(defn hset!
  "Sets the fields of a hash, and returns how many were new.
   - conn: the connection, the value of the component
   - k: the key of the hash
   - field-map: a map from the fields to their values"
  [conn
   k
   field-map]
  (wcar* conn (apply car/hset k (mapcat identity field-map))))

(defn hget!
  "Returns the value of a field of a hash, or nil.
   - conn: the connection, the value of the component
   - k: the key of the hash
   - field: the field"
  [conn
   k
   field]
  (wcar* conn (car/hget k field)))

(defn hgetall!
  "Returns the fields of a hash and their values as a map, or nil when there
   is no such hash.
   - conn: the connection, the value of the component
   - k: the key of the hash"
  [conn k]
  (let [flat (wcar* conn (car/hgetall k))]
    (when (seq flat)
      (apply hash-map flat))))

(defn hdel!
  "Removes some fields from a hash, and returns how many were there.
   - conn: the connection, the value of the component
   - k: the key of the hash
   - fields: the fields"
  [conn
   k
   & fields]
  (wcar* conn (apply car/hdel k fields)))

;; Lists

(defn lpush!
  "Puts values at the head of a list, and returns its new length.
   - conn: the connection, the value of the component
   - k: the key of the list
   - values: the values, which are pushed one after the other"
  [conn
   k
   & values]
  (wcar* conn (apply car/lpush k values)))

(defn rpush!
  "Puts values at the tail of a list, and returns its new length.
   - conn: the connection, the value of the component
   - k: the key of the list
   - values: the values, in the order they are to have"
  [conn
   k
   & values]
  (wcar* conn (apply car/rpush k values)))

(defn lrange!
  "Returns the elements of a list from one position to another, both included.
   A negative position counts from the end, so -1 is the last.
   - conn: the connection, the value of the component
   - k: the key of the list
   - start: the first position
   - stop: the last position"
  [conn
   k
   start
   stop]
  (wcar* conn (car/lrange k start stop)))

(defn llen!
  "Returns the length of a list, zero when there is none.
   - conn: the connection, the value of the component
   - k: the key of the list"
  [conn k]
  (wcar* conn (car/llen k)))

;; Health and failures

(defn ready?
  "Returns true when Redis answers a PING, and false when it does not or cannot
   be reached. It never throws, so a readiness check can call it.
   - conn: the connection, the value of the component"
  [conn]
  (try
    (ping conn)
    (catch Exception _
      false)))

(defn error-data
  "Returns the failure of a Carmine exception as data, with an :error keyword
   such as :wrong-type or :timeout, or nil when the exception is not one. See
   borba.redis.errors.
   - e: the exception"
  [e]
  (errors/error-data e))
