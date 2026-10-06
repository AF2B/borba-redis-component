(ns borba.redis.config-test
  (:require
   [borba.redis.config :as config]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- invalid-option
  "Returns the option that the options are refused for, or nil."
  [options]
  (try (config/connection-options options)
       nil
       (catch clojure.lang.ExceptionInfo e
         (when (= :borba.redis.config/invalid-option (:error (ex-data e)))
           (:option (ex-data e))))))

(deftest connection-options-test
  (testing "has limits that fail soon by default"
    (is (= {:spec    {:host            "localhost"
                      :port            6379
                      :db              0
                      :conn-timeout-ms 2000
                      :read-timeout-ms 5000}
            :pool    {:max-total-per-key 16
                      :max-idle-per-key  16
                      :min-idle-per-key  0}
            :address "localhost:6379/0"}
           (config/connection-options {}))))

  (testing "takes what it is told"
    (is (= {:spec    {:host            "redis.internal"
                      :port            6380
                      :db              3
                      :conn-timeout-ms 100
                      :read-timeout-ms 200
                      :username        "app"
                      :password        "secret"
                      :ssl-fn          :default}
            :pool    {:max-total-per-key 4
                      :max-idle-per-key  4
                      :min-idle-per-key  1}
            :address "redis.internal:6380/3"}
           (config/connection-options {:host                  "redis.internal"
                                       :port                  6380
                                       :database              3
                                       :username              "app"
                                       :password              "secret"
                                       :ssl?                  true
                                       :connection-timeout-ms 100
                                       :read-timeout-ms       200
                                       :max-connections       4
                                       :min-idle              1}))))

  (testing "has no credentials, and no TLS, unless they are given"
    (let [spec (:spec (config/connection-options {}))]
      (is (not-any? #(contains? spec %) [:username :password :ssl-fn])))))

(deftest invalid-options-test
  (testing "the host is a string that is not blank"
    (doseq [bad ["" "  " 5 :redis]]
      (is (= :host (invalid-option {:host bad})) (pr-str bad))))

  (testing "the port is an integer from 1 to 65535"
    (doseq [bad [0 -1 65536 "6379" 6379.5]]
      (is (= :port (invalid-option {:port bad})) (pr-str bad))))

  (testing "the database is zero or more"
    (is (= :database (invalid-option {:database -1})))
    (is (= :database (invalid-option {:database "0"}))))

  (testing "the credentials are strings that are not empty, or not given"
    (is (= :username (invalid-option {:username ""})))
    (is (= :username (invalid-option {:username 5})))
    (is (= :password (invalid-option {:password ""})))
    (is (= :password (invalid-option {:password 5}))))

  (testing "TLS is true or false"
    (is (= :ssl? (invalid-option {:ssl? "yes"}))))

  (testing "the timeouts and the size of the pool are positive integers"
    (doseq [option [:connection-timeout-ms :read-timeout-ms :max-connections]
            bad    [0 -1 1.5 "10"]]
      (is (= option (invalid-option {option bad}))
          (str option " " (pr-str bad)))))

  (testing "the idle connections are zero up to the size of the pool"
    (is (= :min-idle (invalid-option {:min-idle -1})))
    (is (= :min-idle (invalid-option {:max-connections 2 :min-idle 3})))
    (is (nil? (invalid-option {:max-connections 2 :min-idle 2})))))

(deftest password-is-not-in-a-message-test
  (testing "the message never has the password, whatever is wrong"
    (doseq [options [{:password :hunter2}
                     {:password "hunter2" :port 0}
                     {:password "hunter2" :host ""}]]
      (let [message (try (config/connection-options options)
                         (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (not (str/includes? message "hunter2")))))))

(deftest address-test
  (testing "is the host, the port and the database"
    (is (= "a:1/2" (config/address "a" 1 2)))))
