(ns borba.redis.config
  "The options of the connection to Redis: checked, and turned into what
   Carmine takes.

   Every wait has a limit. Carmine waits four seconds to connect, which is the
   limit kept here at two, and waits for a reply for as long as it takes, which
   is how a Redis that stopped answering keeps a thread of the service for ever.
   A command that is not answered in five seconds is an error."
  (:require
   [clojure.string :as str]))

(def default-host
  "Where Redis is, unless told otherwise."
  "localhost")

(def default-port
  "The port Redis listens on, unless told otherwise."
  6379)

(def default-database
  "The database of Redis, unless told otherwise."
  0)

(def default-connection-timeout-ms
  "How long to wait to connect, unless told otherwise."
  2000)

(def default-read-timeout-ms
  "How long to wait for the reply to a command, unless told otherwise."
  5000)

(def default-max-connections
  "The most connections the pool opens, unless told otherwise."
  16)

(def default-min-idle
  "The fewest idle connections the pool keeps, unless told otherwise."
  0)

(def ^:private max-port 65535)

(defn- invalid-option
  [option
   value
   expected]
  (ex-info (str ":" (name option) " is "
                (if (= :password option) "not valid" (pr-str value))
                ", and must be " expected)
           {:error  ::invalid-option
            :option option}))

(defn- positive-int?
  [value]
  (and (int? value) (pos? value)))

(defn- optional-string?
  [value]
  (or (nil? value) (and (string? value) (seq value))))

(defn address
  "Returns where the connection goes, as host:port/database, which is what is
   safe to log: it never has the credentials.
   - host: the host of Redis
   - port: the port of Redis
   - database: the number of the database"
  [host
   port
   database]
  (str host ":" port "/" database))

(defn connection-options
  "Checks the options of the component and returns the options of Carmine that
   they make: the :spec of the connection, the :pool options, and the :address
   to log. Fails naming the first option that is not valid; the value of the
   password is never in the message.
   - host: the host of Redis (default \"localhost\")
   - port: the port of Redis (default 6379)
   - username: the user, for Redis 6 and later (default none)
   - password: the password (default none)
   - database: the number of the database (default 0)
   - ssl?: whether to use TLS (default false)
   - connection-timeout-ms: how long to wait to connect (default 2000)
   - read-timeout-ms: how long to wait for the reply to a command, which is
     the longest a blocking command such as BLPOP can be told to wait
     (default 5000)
   - max-connections: the most connections in the pool (default 16)
   - min-idle: the fewest idle connections the pool keeps (default 0)"
  [{:keys [host port username password database ssl? connection-timeout-ms
           read-timeout-ms max-connections min-idle]
    :or   {host                  default-host
           port                  default-port
           database              default-database
           ssl?                  false
           connection-timeout-ms default-connection-timeout-ms
           read-timeout-ms       default-read-timeout-ms
           max-connections       default-max-connections
           min-idle              default-min-idle}}]
  (when-not (and (string? host) (not (str/blank? host)))
    (throw (invalid-option :host host "a non-empty string")))
  (when-not (and (int? port) (<= 1 port max-port))
    (throw (invalid-option :port port (str "an integer from 1 to " max-port))))
  (when-not (and (int? database) (not (neg? database)))
    (throw (invalid-option :database database
                           "the number of a database, zero or more")))
  (when-not (optional-string? username)
    (throw (invalid-option :username username
                           "a non-empty string, or not given")))
  (when-not (optional-string? password)
    (throw (invalid-option :password password
                           "a non-empty string, or not given")))
  (when-not (boolean? ssl?)
    (throw (invalid-option :ssl? ssl? "true or false")))
  (doseq [[option value] [[:connection-timeout-ms connection-timeout-ms]
                          [:read-timeout-ms read-timeout-ms]
                          [:max-connections max-connections]]]
    (when-not (positive-int? value)
      (throw (invalid-option option value "a positive integer"))))
  (when-not (and (int? min-idle) (not (neg? min-idle))
                 (<= min-idle max-connections))
    (throw (invalid-option :min-idle min-idle
                           "an integer from zero to :max-connections")))
  {:spec    (cond-> {:host            host
                     :port            port
                     :db              database
                     :conn-timeout-ms connection-timeout-ms
                     :read-timeout-ms read-timeout-ms}
              username (assoc :username username)
              password (assoc :password password)
              ssl?     (assoc :ssl-fn :default))
   :pool    {:max-total-per-key max-connections
             :max-idle-per-key  max-connections
             :min-idle-per-key  min-idle}
   :address (address host port database)})
