(ns borba.redis.errors
  "The failures of Redis as data, in the convention of borba.railway: a map with
   an :error keyword that a caller matches on.

     (try (redis/lpush! conn \"a-string-key\" \"x\")
          (catch Exception e
            (errors/error-data e)))
     ;; => {:error :wrong-type}

   The map never has the message of the exception, which can name keys and
   values, and never the credentials."
  (:import
   (java.net SocketTimeoutException)))

(def ^:private reply-prefix->error
  "The error of each prefix of a reply of Redis that a program can do
   something about."
  {:wrongtype  :wrong-type
   :noauth     :authentication-failed
   :wrongpass  :authentication-failed
   :noperm     :permission-denied
   :readonly   :read-only
   :oom        :out-of-memory
   :loading    :loading
   :busy       :busy
   :misconf    :misconfigured
   :err        :command-error})

(def ^:private connection-error-message "Carmine connection error")

(defn- root-causes
  "Returns the exception and its causes, from the outside in."
  [^Throwable e]
  (take-while some? (iterate ex-cause e)))

(defn error-data
  "Returns the failure of an exception of Carmine as data, or nil when the
   exception is not one. The :error is :wrong-type, :authentication-failed,
   :permission-denied, :read-only, :out-of-memory, :loading, :busy,
   :misconfigured or :command-error for a reply of Redis that is an error;
   :timeout when the connection or the reply took longer than the limit; and
   :connection-failure when Redis could not be reached.
   - e: the exception"
  [e]
  (let [causes (root-causes e)]
    (or
     (when-let [prefix (some #(:prefix (ex-data %)) causes)]
       {:error (get reply-prefix->error prefix :redis-error)
        :prefix prefix})

     (when (some #(= connection-error-message (ex-message %)) causes)
       {:error (if (some #(instance? SocketTimeoutException %) causes)
                 :timeout
                 :connection-failure)})

     (when (some #(instance? SocketTimeoutException %) causes)
       {:error :timeout}))))
