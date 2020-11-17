(ns concurrently.core
  (:require [clojure.core :as core]
            [clojure.core.async :refer [go go-loop chan timeout close! alt! >! <! <!!] :as async]
            [clojure.tools.logging :as log]
            [databox.core :as box])
  (:import [java.util UUID]))


(defmacro take-or-throw!
  [result-ch timeout-ms & [context-name]]
  `(let [context-name# ~context-name
         result-ch# ~result-ch
         timeout-ms# ~timeout-ms]
     (if (= timeout-ms# :no-timeout)
       ;; There are no timeout setting.
       (<! result-ch#)

       ;; read-timeout active.
       (alt!
         result-ch#
         ([value#]
          value#)

         (timeout timeout-ms#)
         ([v#]
          (let [msg# (if context-name#
                       (str "channel read timeout! context = " context-name#)
                       "channel read timeout!")]
            (throw (ex-info msg# {:reason ::channel-timeout}))))))))

(defn chain
  "Create a channel connected to a transducer.
  Works like `pipe`, but read continuously from input even if an output
  channel is closed.
  If an input channel is closed, output will be closed too."
  [source-ch xf & [ex-handler]]
  (let [next-ch (chan 1 xf ex-handler)]
    (go-loop []
      (let [item (<! source-ch)]
        (if (nil? item)
          (close! next-ch)
          (do
            (>! next-ch item)
            (recur)))))
    next-ch))

(defn- transaction-id
  []
  (str (UUID/randomUUID)))

(defn- data-end
  [transaction-id]
  (-> (box/success ::data-end)
      (assoc :transaction-id transaction-id)))

(defn data-end?
  [boxed]
  (= ::data-end (box/success-value boxed)))

(def current-concurrent-count (ref 0))

(add-watch current-concurrent-count
           ::concurrent-counter
           (fn [_ _ old-value new-value]
             (log/info (format "Concurrent count %d -> %d" old-value new-value))))

(defn cleanup-in-background
  "Slurp all data in a channel and abandon them silently."
  [ch]
  (go-loop []
    (when (<! ch)
      (recur))))

(def jobs (atom #{}))

(defn job-cancelled?
  [transaction-id]
  (nil? (@jobs transaction-id)))

(defn- registar-job
  [transaction-id]
  (swap! jobs conj transaction-id))

(defn- unregistar-job
  [transaction-id]
  (swap! jobs disj transaction-id))

(defprotocol Cancellable
  (cancel [job]))

(extend-protocol Cancellable
  nil
  (cancel [job] nil))

(defrecord ConcurrentJob [channel transaction-id]
  Cancellable
  (cancel [self]
    (when transaction-id
      (swap! jobs disj transaction-id))))

(defn concurrent-job
  ([channel id]
   (->ConcurrentJob channel id))
  ([channel]
   (->ConcurrentJob channel nil)))

(defn concurrently
  [{:keys [input-ch] :as context} items-ch options]
  (assert (some? input-ch))

  (if-not items-ch
    (async/to-chan [])

    (let [{:keys [ignore-error? timeout-ms context-name]
           next-ch :channel
           :or   {timeout-ms    120000
                  ignore-error? false
                  next-ch       (chan 1)
                  context-name  "none"}} options

          transaction-id (transaction-id)

          ;; Convert items of an items-ch to databoxes and assign a :channel key to the generated databoxes.
          ;; The :channel is a channel where calculation-results spit on.
          ;; And then append a 'dataend' databox as the last item of the items-ch.
          data-end-boxed (-> (data-end transaction-id)
                             (assoc :channel next-ch
                                    :context-name context-name))
          requests-ch    (chain items-ch
                                (map (fn [item] (-> (box/box item)
                                                    (merge options)
                                                    (assoc :channel next-ch
                                                           :context-name context-name
                                                           :ignore-error? ignore-error?
                                                           :transaction-id transaction-id)))))
          ;; FOR DEBUG USE
          ;; A count incremented by each concurrently calls.
          counted        (ref false)
          count-up-if-first (fn []
                              (dosync
                               (when-not (ensure counted)
                                 (alter current-concurrent-count inc)
                                 (commute counted (fn [_] true)))))]
      
      ;; Registar a job.
      ;; Jobs can be cancelled by a `cancel` function of ConcurrentJob.
      (registar-job transaction-id)

      ;; Spit all input data onto a concurrent pipeline.
      ;; Calculation results will be spitted onto a output channel of the pipeline.
      ;; the results will be handled in a go-block in a `make-concurrent-process` function,
      ;; and then are spitted onto a :channel.
      (go
        (try
          (log/debug (str "start concurrent action [" context-name "]"))
          (loop []
            (when-let [data (take-or-throw! requests-ch timeout-ms (str context-name " [writing]"))]
              (if (>! input-ch data)
                (do
                  (count-up-if-first)
                  (recur))
                (log/debug "input-ch is closed."))))
          (catch Throwable th
            (when (>! input-ch (-> (box/failure th)
                                   (assoc :channel next-ch
                                          :context-name context-name
                                          :transaction-id transaction-id)))
              (count-up-if-first)))
          (finally
            (cleanup-in-background requests-ch)
            (if (>! input-ch data-end-boxed)
              (count-up-if-first)
              (throw (ex-info (str "Couldn't write a data-end. context = " context ", transaction-id = " transaction-id) {:transaction-id transaction-id, :context context}))))))

      (->ConcurrentJob next-ch transaction-id))))

(defn- make-process-context
  [input-ch]
  {:input-ch input-ch})


(defn- handle-pipeline-data
  [{:keys [transaction-id] :as data} xf]
  (log/debug "pipeline")
  (cond
    (data-end? data)
    data

    (job-cancelled? transaction-id)
    (do
      (log/debug (str "a job already is cancelled. transaction-id = " transaction-id))
      (box/map data (fn [_] ::skipped)))

    :else
    (let [options (-> data
                      (box/strip-default-keys)
                      (dissoc :channel :transaction-id :context-name))]
      (box/map data #(->> (sequence xf [{:data % :options options}])
                          (first))))))


(def ^:private pipeline-fn {:blocking async/pipeline-blocking
                            :default  async/pipeline})

(defn- make-concurrent-process
  [pipeline-type parallel-count output-ch xf input-ch]
  (let [pipeline (or (pipeline-fn pipeline-type) 
                     (throw (ex-info (str "no such pipeline-type: " pipeline-type) 
                                     {:pipeline-type pipeline-type})))]
    ;; Start a concurrent pipeline backed by `pipeline-*` fns of core.async and
    ;; return a Process Context.
    ;; This Process Context should be shared in an application.
    ;; A Process Context runs actions in restricted number of threads in a same time.
    ;; All manner of this pipeline depend on `pipeline` of core.async.
    ;; Data retrieved from the output channel of this pipeline always are databoxes
    ;; which containing a :channel key. This :chanel is a channel where a calculation result
    ;; wrapped by databoxes should be spitted on.
    ;;
    ;; A go-loop started in this function slurps all databoxes from the output channel of a pipeline,
    ;; and spits the databoxes onto the :channel.
    (pipeline parallel-count
              output-ch
              (map (fn [data] (handle-pipeline-data data xf)))
              input-ch))

  (let [pipeline-ch (chain output-ch (box/filter #(not= % ::skipped)))]
    ;; A go-loop slurping all databoxes from the output channel of the pipeline generated by code above.
    (go-loop []
      (when-let [{:keys [ignore-error? context-name transaction-id], out-ch :channel :as item-boxed}
                 (<! pipeline-ch)]
        (assert out-ch)
        (log/debug "retriever loop")
        (cond
          (and (box/failure? item-boxed) ignore-error?)
          (log/warn (:exception item-boxed) "Error in an async pipeline, But ignored.")
          
          (data-end? item-boxed)
          (do
            (log/debug (str "closing channels [" context-name "]"))
            (unregistar-job transaction-id)
            (dosync (alter current-concurrent-count dec))
            (close! out-ch))
          
          :else
          (>! out-ch item-boxed))
        
        (recur))))

  ;; Return a Process Context
  (make-process-context input-ch))


(defn concurrent-process-blocking
  "Create a concurrent process backed by core.async/pipeline-blocking.
   `f` must be a function of two arguments. the first is a value retrieved a pipeline.
   the second is a options-map supplied to `concurrently` function.
   You should use this function if the `f` is a blocking function."
  [parallel-count output-ch f input-ch]
  (make-concurrent-process :blocking parallel-count output-ch f input-ch))

(defn concurrent-process
  "Create a concurrent process backed by core.async/pipeline.
   `f` must be a function of two arguments. the first is a value retrieved a pipeline.
   the second is a options-map supplied to `concurrently` function.
   `f` should be CPU-bounded, should not run blocking actions in `f`"
  [parallel-count output-ch f input-ch]
  (make-concurrent-process :default parallel-count output-ch f input-ch))

(defn get-results
  "Safely read all data from a channel and return a vector containing all read data.
   the items read from a channel must be databoxes. The result vector contains 
   unboxed data of the read items. If an exception occurred while resolving read items, 
   an exception will be thrown.
   
   This function will throw an exception if :timeout-ms option value isn't :no-timeout and no data available
   from the 'ch' channel after the :timeout-ms.
   The :catch function will be called if a databox contains an exception, then returns a failure databox.
   The :finally function will be called always.
   
   'ch' will be read fully even if this function returns early before reading all data from 'ch',  
   because a go-block is launched automatically for reading 'ch' fully.
   So a pipeline backing the 'ch' never be stacked by never-read-data remained in a pipeline."
  [ch & [{catch-fn :catch finally-fn :finally context-name :context-name timeout-ms :timeout-ms :or {context-name "none" timeout-ms 120000}}]]
  @(<!! (go
          (try
            (loop [results []]
              (log/debug "get-results loop")
              (if-let [item (take-or-throw! ch timeout-ms context-name)]
                (recur (conj results @item))
                (box/success results)))
            (catch Throwable ex
              (log/debug "close")
              (when catch-fn
                (catch-fn ex))
              (box/failure ex))
            (finally
              (log/debug "finally")
              (cleanup-in-background ch)
              (when finally-fn
                (finally-fn)))))))
