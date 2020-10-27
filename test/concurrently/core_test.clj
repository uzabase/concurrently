(ns concurrently.core_test
  (:require [concurrently.core :refer [concurrent-process concurrent-process-blocking concurrently get-results cancel]]
            [clojure.test :refer [deftest testing is]]
            [clojure.core.async :refer [chan to-chan <!!]]
            [clojure.string :refer [upper-case]])
  (:import [clojure.lang ExceptionInfo]))


(defn- has-same-key-values?
  [m1 m2]
  (let [m2keys (keys m2)
        m1-selected (select-keys m1 m2keys)]
    (= m1-selected m2)))

(deftest test-pipeline
  (testing "can read all data successfully"
    (let [pipeline-input-ch (chan)
          pipeline-output-ch (chan)
          context (concurrent-process-blocking
                   3
                   pipeline-output-ch
                   (map (fn [{:keys [data]}] (upper-case data)))
                   pipeline-input-ch)
          data-coll ["a" "b" "c"]
          {:keys [channel] :as job} (concurrently context (to-chan data-coll) {})]
      (try
        (let [results (get-results channel)]
          (is (= ["A" "B" "C"] results)))
        (finally
          (cancel job)))))

  (testing "channel will be closed if an exception occurred at first item"
    (let [pipeline-input-ch (chan)
          pipeline-output-ch (chan)
          counter (atom 0)
          context (concurrent-process-blocking
                   1
                   pipeline-output-ch
                   (map (fn [{:keys [data]}]
                          (let [c (swap! counter inc)]
                            (if (= c 1)
                              (throw (ex-info "test error" {}))
                              data))))
                   pipeline-input-ch)
          data-coll ["a" "b" "c"]
          {:keys [channel] :as job} (concurrently context (to-chan data-coll) {})]
      (try
        (is (thrown? ExceptionInfo (get-results channel)))
        (Thread/sleep 3)
        (is (nil? (<!! channel)))
        (finally
          (cancel job)))))

  (testing "all options passed to 'concurrently' must be merged into a 'options' parameter of pipeline function."
    (let [pipeline-input-ch (chan)
          pipeline-output-ch (chan)
          test-options {:test "option"}
          context (concurrent-process
                   3
                   pipeline-output-ch
                   (map (fn [{:keys [options]}] options))
                   pipeline-input-ch)
          data-coll ["a" "b" "c"]
          {:keys [channel] :as job} (concurrently context (to-chan data-coll) test-options)]
      (try
        (let [results (get-results channel)]
          (is (has-same-key-values? (nth results 0) test-options))
          (is (has-same-key-values? (nth results 1) test-options))
          (is (has-same-key-values? (nth results 2) test-options)))
        (finally
          (cancel job))))))