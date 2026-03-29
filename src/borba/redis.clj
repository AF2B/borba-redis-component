(ns borba.redis
  "Integrant component for Redis via Carmine.

   Registers :components/redis with a Carmine connection spec
   exposed as :redis in the component map.

   Provides a wcar* macro and common operation wrappers so callers
   don't need to import Carmine directly.

   Usage:
     (let [{:keys [redis]} components]
       (redis/get! redis \"my-key\")
       (redis/set! redis \"my-key\" \"value\" {:ttl-seconds 300}))"
  (:require [integrant.core :as ig]
            [taoensso.carmine :as car]))

;; ── Integrant lifecycle ──────────────────────────────────────────────────────

(defmethod ig/init-key :components/redis
  [_ {:keys [host port]}]
  (let [conn {:pool {}
              :spec {:host (or host "localhost")
                     :port (or port 6379)}}]
    (println "🔴 [redis] Connected →" host ":" port)
    conn))

(defmethod ig/halt-key! :components/redis
  [_ _conn]
  (println "🔴 [redis] Disconnected"))

;; ── Core macro ───────────────────────────────────────────────────────────────

(defmacro wcar*
  "Execute one or more Redis commands using the component connection.

   Example:
     (redis/wcar* conn
       (car/set \"k\" \"v\")
       (car/get \"k\"))"
  [conn & body]
  `(car/wcar ~conn ~@body))

;; ── Read operations ──────────────────────────────────────────────────────────

(defn get!
  "Returns the value at key, or nil if not found."
  [conn k]
  (wcar* conn (car/get k)))

(defn mget!
  "Returns values for multiple keys as a vector."
  [conn & keys]
  (wcar* conn (apply car/mget keys)))

(defn exists?
  "Returns true if the key exists in Redis."
  [conn k]
  (= 1 (wcar* conn (car/exists k))))

(defn ttl!
  "Returns the remaining TTL in seconds for key (-1 if no TTL, -2 if missing)."
  [conn k]
  (wcar* conn (car/ttl k)))

;; ── Write operations ─────────────────────────────────────────────────────────

(defn set!
  "Sets key to value.
   Opts:
     :ttl-seconds — expire the key after N seconds (EX)
     :nx?         — only set if key does not exist (NX)
     :xx?         — only set if key already exists (XX)

   Example:
     (redis/set! conn \"session:abc\" token {:ttl-seconds 3600})"
  ([conn k v]
   (wcar* conn (car/set k v)))
  ([conn k v {:keys [ttl-seconds nx? xx?]}]
   (wcar* conn
          (cond
            (and ttl-seconds nx?) (car/set k v :ex ttl-seconds :nx)
            (and ttl-seconds xx?) (car/set k v :ex ttl-seconds :xx)
            ttl-seconds           (car/set k v :ex ttl-seconds)
            nx?                   (car/set k v :nx)
            xx?                   (car/set k v :xx)
            :else                 (car/set k v)))))

(defn del!
  "Deletes one or more keys. Returns the number of deleted keys."
  [conn & keys]
  (wcar* conn (apply car/del keys)))

(defn expire!
  "Sets the TTL of key to ttl-seconds."
  [conn k ttl-seconds]
  (wcar* conn (car/expire k ttl-seconds)))

(defn incr!
  "Atomically increments key by 1. Returns new value."
  [conn k]
  (wcar* conn (car/incr k)))

(defn incrby!
  "Atomically increments key by amount. Returns new value."
  [conn k amount]
  (wcar* conn (car/incrby k amount)))

;; ── Hash operations ──────────────────────────────────────────────────────────

(defn hset!
  "Sets fields in a hash. field-map is a Clojure map.

   Example:
     (redis/hset! conn \"user:1\" {:name \"Ana\" :email \"ana@borba.com\"})"
  [conn k field-map]
  (wcar* conn (apply car/hset k (mapcat identity field-map))))

(defn hget!
  "Returns the value of a single hash field."
  [conn k field]
  (wcar* conn (car/hget k field)))

(defn hgetall!
  "Returns all fields and values of a hash as a map."
  [conn k]
  (let [flat (wcar* conn (car/hgetall k))]
    (when (seq flat)
      (apply hash-map flat))))

(defn hdel!
  "Removes one or more fields from a hash."
  [conn k & fields]
  (wcar* conn (apply car/hdel k fields)))

;; ── List operations ──────────────────────────────────────────────────────────

(defn lpush!
  "Prepends values to list at key."
  [conn k & values]
  (wcar* conn (apply car/lpush k values)))

(defn rpush!
  "Appends values to list at key."
  [conn k & values]
  (wcar* conn (apply car/rpush k values)))

(defn lrange!
  "Returns elements of list from start to stop (inclusive). -1 = end."
  [conn k start stop]
  (wcar* conn (car/lrange k start stop)))

(defn llen!
  "Returns the length of the list at key."
  [conn k]
  (wcar* conn (car/llen k)))
