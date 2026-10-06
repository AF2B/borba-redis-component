(ns borba.redis-test
  (:require
   [borba.redis :as redis]
   [clojure.test :refer [deftest is testing]]))

(defn- thrown-data
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest set-options-test
  (testing "refuses options that Redis would refuse, before sending them"
    (let [invalid :borba.redis/invalid-set-options]
      (is (= invalid
             (:error (thrown-data
                      #(redis/set! nil "k" "v" {:nx? true :xx? true})))))
      (doseq [ttl [0 -1 1.5 "10"]]
        (is (= invalid (:error (thrown-data
                                #(redis/set! nil "k" "v" {:ttl-seconds ttl}))))
            (pr-str ttl))))))
