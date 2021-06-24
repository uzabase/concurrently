(ns concurrently.async
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async :refer [go-loop close! >! <! chan mix
                                        mix Mux muxch* Mix admix* unmix* unmix-all* toggle* solo-mode* chan]]
            [clojure.tools.logging :as log])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]))

(defn channel? [ch] (instance? ManyToManyChannel ch))

(s/def ::close? boolean?)
(s/def ::full-read? boolean?)
(s/def ::connect-options (s/keys :opt-un [::close? ::full-read?]))

(s/fdef pipe
  :args (s/cat :from channel? :to channel? :options (s/? ::connect-options)))

(defn pipe
  "Same with 'pipe' fn of core.async, but never stop reading if the next channel is closed."
  ([from to] (pipe from to {}))
  ([from to {:keys [close? full-read?] :or {close? true full-read? true}}]
   (go-loop []
     (let [v (<! from)]
       (if (nil? v)
         (when close? (close! to))
         (let [written? (>! to v)]
           (when (or full-read? written?)
             (recur))))))
   to))


(defprotocol Freezable
  (freeze [target-mix] "Freeze the supplied mix object."))

(defn freezable-mix
  "Create a mix object which is able to stop accepting 'admix' call after freezed.
   This mix is freezable by calling 'freeze' on this mix object.
   After the freezing, following admix call throw an exception.
   If all channel admixed before freezing are closed, the channel this mix object contains
   must be closed."
  [out & [{:keys [full-read?] :or {full-read? false}}]]
  (let [mix-out-ch (if full-read? (chan 1) out)]
    (when full-read?
      ;; If fully-read? is true, all admixed channels must be fully read
      ;; even if the output channel is closed.
      ;; To continue reading channels, create a surrogate channel and 
      ;; start a go-loop which read the surrogate channel until the end,
      ;; then use the channel onto mix.
      (go-loop []
        (when-let [item (<! mix-out-ch)]
          (>! out item)
          (recur))))

    (let [inner-mix (mix mix-out-ch) ; a mix object of default clojure implementation.
          state (atom {:channels #{}
                       :freezed false})]

      ;; Create a mix object and delegate all function calls to the inner-mix
      ;; except of admix*.
      ;; admix* must handle all added channels and watch the closing of the channels.
      ;; if all channels are closed after freezing this mix, a downstream channel must be
      ;; closed.
      (reify
        Mux
        (muxch* [_] (muxch* inner-mix))
        Mix
        (admix*
          [_ ch]
          (if (true? (:freezed @state))
            (throw (ex-info "This mix already is freezed. You can not admix more."))
            (letfn [(complete
                      [ch]
                      (let [{:keys [freezed channels]} (swap! state update :channels disj ch)]
                        (log/debug "channels count in freezable mix:" (count channels))
                       ;; If this mix already is freezed and no channels remain,
                       ;; close the downstream channel which this mix hold.
                        (when (and (true? freezed)
                                   (not (seq channels)))
                          (close! out))))]

              (let [{:keys [channels]} (swap! state update :channels conj ch)]
                (log/debug "channels count in freezable mix:" (count channels)))

              (let [completable-ch (chan)]
                ;; Create a new channel which call 'complete' when the input channel is fully read.
                ;; Supply the created channel to admix* instead of the input channel.
                (go-loop []
                  (if-let [item (<! ch)]
                    (do
                      (>! completable-ch item)
                      (recur))
                    (do
                      (close! completable-ch)
                      (complete ch))))
                (admix* inner-mix completable-ch)))))

        (unmix* [_ ch] (unmix* inner-mix ch))
        (unmix-all* [_] (unmix-all* inner-mix))
        (toggle* [_ state-map] (toggle* inner-mix state-map))
        (solo-mode* [_ mode] (solo-mode* inner-mix mode))

        Freezable
        (freeze
          [_]
          (let [{:keys [channels]} (swap! state assoc :freezed true)]
            (log/debug "channels count in freezable mix:" (count channels))
           ;; Mix is freezed. In this time, all admixed channels already are closed,
           ;; close the downstream channel which this mix hold. 
            (when-not (seq channels)
              (close! out))))))))
