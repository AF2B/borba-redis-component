(ns borba.redis.errors-test
  (:require
   [borba.redis.errors :as errors]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.net ConnectException SocketTimeoutException)))

(defn- reply-error
  "An exception as Carmine throws the error reply of Redis."
  [prefix message]
  (ex-info message {:ns "taoensso.carmine.protocol" :prefix prefix}))

(defn- connection-error
  "An exception as Carmine throws a failure to connect or to read."
  [cause]
  (ex-info "Carmine connection error" {} cause))

(deftest reply-test
  (testing "names the errors a program can do something about"
    (doseq [[prefix expected] {:wrongtype :wrong-type
                               :noauth    :authentication-failed
                               :wrongpass :authentication-failed
                               :noperm    :permission-denied
                               :readonly  :read-only
                               :oom       :out-of-memory
                               :loading   :loading
                               :busy      :busy
                               :misconf   :misconfigured
                               :err       :command-error}]
      (is (= {:error expected :prefix prefix}
             (errors/error-data (reply-error prefix "x"))))))

  (testing "calls the rest a Redis error, with its prefix"
    (is (= {:error :redis-error :prefix :crossslot}
           (errors/error-data (reply-error :crossslot "x"))))))

(deftest connection-test
  (testing "knows when Redis could not be reached"
    (is (= {:error :connection-failure}
           (errors/error-data
            (connection-error (ConnectException. "refused"))))))

  (testing "knows when it took too long, to connect or to answer"
    (is (= {:error :timeout}
           (errors/error-data
            (connection-error (SocketTimeoutException. "Read timed out")))))
    (is (= {:error :timeout}
           (errors/error-data (SocketTimeoutException. "Read timed out"))))))

(deftest message-test
  (testing "never has the message, which can name a key or a value"
    (is (not-any? #(and (string? %) (re-find #"secret-key" %))
                  (vals (errors/error-data
                         (reply-error :wrongtype
                                      "WRONGTYPE on secret-key")))))))

(deftest cause-test
  (testing "finds the failure in the causes"
    (is (= {:error :wrong-type :prefix :wrongtype}
           (errors/error-data
            (RuntimeException. "wrapped" (reply-error :wrongtype "x"))))))

  (testing "is nil for what is not from Carmine"
    (is (nil? (errors/error-data (IllegalStateException. "no"))))
    (is (nil? (errors/error-data (ex-info "no" {}))))))
