(ns concurrently.core_test
  (:require [concurrently.core :refer [concurrent-process concurrent-process-blocking concurrently get-results cancel cleanup-in-background]]
            [clojure.test :refer [deftest testing is]]
            [clojure.core.async :refer [chan to-chan <!! timeout ]]
            [clojure.string :refer [upper-case]]
            [databox.core :refer [failure? success? success-value]])
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
        (<!! (timeout 3000))
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
          (cancel job)))))
  
  (testing "Concurrent job is cancellable"
    (let [pipeline-input-ch (chan)
          pipeline-output-ch (chan)
          context (concurrent-process
                   1
                   pipeline-output-ch
                   (map (fn [{:keys [data]}] data))
                   pipeline-input-ch)
          data-coll ["a" "b" "c" "d" "e"]
          {:keys [channel] :as job} (concurrently context (to-chan data-coll) {})]
      (let [v (<!! channel)]
        (is (some? v)))
      (cancel job)
      (let [last-item (atom nil)]
        (loop []
          (when-let [boxed (<!! channel)]
            (prn boxed)
            (reset! last-item boxed)
            (recur)))
        (is (not= "e" (success-value @last-item))))))
  
  (testing "If supplied transducer caused exceptions, the result boxed data become failure box"
    (let [pipeline-input-ch (chan)
          pipeline-output-ch (chan)
          context (concurrent-process
                   1
                   pipeline-output-ch
                   (map (fn [{{:keys [index data]} :data}]
                          (if (zero? (rem index 2))
                            (throw (ex-info "test error" {:index index}))
                            data)))
                   pipeline-input-ch)
          data-coll [{:index 1, :data "a"}
                     {:index 2, :data "b"}
                     {:index 3, :data "c"}
                     {:index 4, :data "d"}]
          {:keys [channel]} (concurrently context (to-chan data-coll) {})
          counter (atom 0)]
      (try
        (loop []
          (when-let [boxed (<!! channel)]
            (let [current-index (swap! counter inc)]
              (case current-index
                1 (do
                    (is (success? boxed))
                    (is (= "a" (success-value boxed))))
                2 (is (failure? boxed))
                3 (do
                    (is (success? boxed))
                    (is (= "c" (success-value boxed))))
                4 (is (failure? boxed))))
            (recur)))
        (finally
          (cleanup-in-background channel)
          (is (= 4 @counter)))))))

(deftest test-for-get-results
  (testing "catch block must be called if a first failure box is found"
    (let [pipeline-input-ch (chan)
          pipeline-output-ch (chan)
          context (concurrent-process
                   1
                   pipeline-output-ch
                   (map (fn [{:keys [data]}]
                          (if (= "b" data)
                            (throw (ex-info "test error" {:value :catch-test}))
                            data)))
                   pipeline-input-ch)
          data-coll ["a" "b" "c"]
          test-refs (atom {:catch-called? false
                           :exception-thrown? false})
          {:keys [channel]} (concurrently context (to-chan data-coll) {})]
      (try
        (get-results channel
                     {:catch (fn [_] (swap! test-refs update :catch-called? (fn [_] true)))})
        (catch Exception ex
          (is :catch-test (:value (ex-data ex)))
          (swap! test-refs update :exception-thrown? (fn [_] true))))
      (is (true? (:catch-called? @test-refs)))
      (is (true? (:exception-thrown? @test-refs)))))

  (testing "finally fn must be called if it is supplied as a part of options for get-results"
    (let [pipeline-input-ch (chan)
          pipeline-output-ch (chan)
          context (concurrent-process
                   1
                   pipeline-output-ch
                   (map (fn [{:keys [data]}] data))
                   pipeline-input-ch)
          data-coll ["a" "b" "c"]
          finally-block-called? (atom false)
          {:keys [channel]} (concurrently context (to-chan data-coll) {})]
      (get-results channel
                   {:finally #(reset! finally-block-called? true)})
      (is (true? @finally-block-called?))))

  (testing "timeout exception must be thrown if the :timeout-ms option for get-results is not :no-timeout and a specified timeout-ms passed before getting an item from channel"
    (let [pipeline-input-ch (chan)
          pipeline-output-ch (chan)
          context (concurrent-process
                   1
                   pipeline-output-ch
                   (map (fn [{:keys [data]}] (<!! (timeout 1000)) data))
                   pipeline-input-ch)
          data-coll ["a" "b" "c"]
          {:keys [channel]} (concurrently context (to-chan data-coll) {})]
      (try
        (get-results channel {:timeout-ms 500})
        (catch ExceptionInfo ex
          (is (= :concurrently.core/channel-timeout (:reason (ex-data ex)))))))))
