(ns user
  (:require [concurrently.core :refer [concurrent-process-blocking concurrently get-results cancel]]
            [clojure.core.async :refer [chan to-chan!]]
            [clojure.tools.logging :as log]
            [taoensso.timbre :as timbre]))

(timbre/merge-config! {:min-level :debug})


(defn run-unordered-test-pipeline
  []
  (let [pipeline-input-ch (chan 1)
        pipeline-output-ch (chan 1)
        context (concurrent-process-blocking
                 3
                 pipeline-output-ch
                 (map (fn [{:keys [data]}]
                        (log/debug "begin:" data)
                        (let [result (if (zero? data)
                                       (do
                                         (Thread/sleep 5000)
                                         data)
                                       data)]
                          (log/debug "end:" data)
                          result)))
                 pipeline-input-ch
                 {:ordered? false})
        data-coll (range 0 10)
        {:keys [channel] :as job} (concurrently context (to-chan! data-coll) {})]
    (try
      (let [results (get-results channel)]
        (log/debug "results:" results)
        results)
      (finally
        (cancel job)))))