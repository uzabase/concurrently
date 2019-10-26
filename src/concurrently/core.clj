(ns concurrently.core
  (:refer-clojure :exclude [concat])
  (:require [clojure.core :as core]
            [clojure.core.async :refer [go go-loop chan to-chan timeout close! alt! >! buffer <! <!! pipe] :as async]
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

(defn concat
  "Connect a channel to a tail of another channel."
  [ch & chs]
  (if-not (seq chs)
    ch
    (let [out-ch (chan)]
      (go-loop [channels (cons ch chs)]
        (if-let [in-ch (first channels)]
          (do
            (loop []
              (when-let [data (<! in-ch)]
                (>! out-ch data)
                (recur)))
            (recur (rest channels)))
          (close! out-ch)))
      out-ch)))

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

(def current-concurrent-count (atom 0))

(add-watch current-concurrent-count
           ::concurrent-counter
           (fn [k reference old-value new-value]
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

    (let [{:keys [insecure? ignore-error? ignore-404? timeout-ms next-ch context-name]
           :or   {timeout-ms    120000
                  next-ch       (chan 1)
                  context-name  "none"}} options

          transaction-id (transaction-id)

          ;; Convert items of an items-ch to databoxes and assign a :channel key to the generated databoxes.
          ;; The :channel is a channel where calculation-results spit on.
          ;; And then append a 'dataend' databox as the last item of the items-ch.
          data-end-boxed (-> (data-end transaction-id)
                             (assoc :channel next-ch
                                    :context-name context-name))
          requests-ch    (concat (chain items-ch
                                        (map (fn [item] (-> (box/value item)
                                                            (merge options)
                                                            (assoc :channel next-ch
                                                                  :context-name context-name
                                                                  :transaction-id transaction-id)))))
                                 (to-chan [data-end-boxed]))] ; append a data-end. Be aware that data-end also have a :channel key.

      ;; FOR DEBUG USE
      ;; A count incremented by each concurrently calls.
      (swap! current-concurrent-count inc)

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
                (recur)
                (log/debug "input-ch is closed."))))
          (catch Throwable th
            (>! input-ch (-> (box/failure th)
                             (assoc :channel next-ch
                                    :context-name context-name
                                    :transaction-id transaction-id)))
            (>! input-ch data-end-boxed))
          (finally
            (cleanup-in-background requests-ch))))

      (->ConcurrentJob next-ch transaction-id))))

(defn- make-process-context
  [input-ch]
  {:input-ch input-ch})


(defn concurrent-process-blocking
  [parallel-count output-ch f input-ch]
  ;; Start a concurrent pipeline backed by `pipeline-blocking` of core.async and
  ;; return a Process Context.
  ;; This Process Context should be shared in an application.
  ;; A Process Context runs actions in restricted number of threads in a same time.
  ;; All manner of this pipeline depend on `pipeline` of core.async.
  ;; Data retrieved from the output channel of this pipeline always are databox
  ;; which containing a :channel key. This :chanel is a channel where a calculation result
  ;; contained by a databox should be spitted on.
  ;;
  ;; A go-loop started in this function slurps all databox from a output channel of a pipeline,
  ;; and spits the databox onto the :channel.
  (async/pipeline-blocking parallel-count
                           output-ch
                           (map (fn [{:keys [transaction-id] :as data}]
                                  (log/debug "pipeline")
                                  (if (data-end? data)
                                    data
                                    (if (job-cancelled? transaction-id)
                                      (do
                                        (log/info (str "a job already is cancelled. transaction-id = " transaction-id))
                                        (box/map data (fn [_] ::skipped)))
                                      (box/map data
                                              #(f % (-> data
                                                        (box/strip-default-keys)
                                                        (dissoc :channel :transaction-id :context-name))))))))
                           input-ch)

  (let [pipeline-ch (chain output-ch (box/filter #(not= % ::skipped)))]

    ;; A go-loop slurping all databox from an output channel of a generated pipeline.
    (go-loop []
      (when-let [{:keys [ignore-error? context-name transaction-id], out-ch :channel :as item-boxed}
                 (<! pipeline-ch)]
        (assert out-ch)
        (log/debug "retriever loop")
        (if (and (box/failure? item-boxed) ignore-error?)
          (log/warn (:exception item-boxed) "Error in an async pipeline, But ignored.")
          (if (data-end? item-boxed)
            ;; Found a data-end. all data for this :channel have come.
            ;; Close the channel.
            (do
              (log/debug (str "closing channels [" context-name "]"))
              (unregistar-job transaction-id)
              (swap! current-concurrent-count dec)
              (close! out-ch))
            (do
              (>! out-ch item-boxed))))

        (recur))))

  ;; Return a Process Context
  (make-process-context input-ch))


(defn get-results
  [ch & [{catch-fn :catch finally-fn :finally context-name :context-name timeout-ms :timeout-ms :or {context-name "none" timeout-ms 120000}}]]
  @(<!! (go
          (try
            (loop [results []]
              (log/debug "handle-results loop")
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
