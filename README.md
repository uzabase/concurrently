# concurrently

A clojure library for making concurrent process-engine backed by core.async.
Application can share the engine and put process-requests onto the engine.
Engine will return a channel containing all processed results for the requests.

This library safely handle pipeline backed by core.async so that pipeline never
stack by abuse of channels like stopping data-retrieving from channels before
fully retrieving all data or forgetting to close channels.

This library backed by databox (https://github.com/tyano/databox) that wrap data in
box and make handling data in channel-pipeline exception-safe. All exceptions
occured in databox safely are wrapped and be passed-through channels until the end
of channel-pipeline, so that users can handle exceptions at the end of pipeline.

`get-results` function handle all actions needed for protecting pipeline from
accidental stacking. `get-results` try to retrieve all data from an output channel
and unwrap databoxes. If an exception were occurred in a pipeline, unwrapping the failure-databox will
throw the exception, but `get-results` handle all data remaining in channel-pipeline for
protecting the pipeline from accidental stacking.

## Usage

### Install

Leiningen:
```
[concurrently "0.2.1"]
```

Clojure CLI:
```
concurrently/concurrently {:mvn/version "0.2.1"}
```

### Create an process-engine by `concurrent-process` function

You can make an engine with a transducer, input and output channels and max parallel count by
calling `concurrent-process` or `concurrent-process-blocking` functions.
All data supplied into input-channel will be handled by a supplied transducer in parallel by
go-blocks or threads of same number with parallel count.
But you should not put data into the input-channel directly.
You can supply data into an engine by another function.

Transducer must accept a map with :data and :options keys.
:data is a value from input channel.
:options is a option-map supplied to `concurrently` function.


### Supply data to the created engine

`concurrent-process` and `concurrent-process-blocking` return a process-context.
You should not use an input channel of pipeline directly.
Instead of that, you should use `concurrently` function with the returned process-context,
a channel which all input data can be read from, and an option map.

`concurrently` wraps all input data by `databox` and concurrent-process handles
the databoxes so that if some exceptions occurred in a supplied transducer, the exception safely
converted to a failure-box and passed-through into pipeline.

### getting calculated results from a Job

`concurrently` returns a `Job`. You can cancel the job by calling `(cancel job)` function.
`Job` contains a field `:channel`. You can read all calculated results for all input data supplied to
`concurrently` function from the channel, but should not read it directly.
Use `(get-results (:channel job))` function for safe-reading from channels.

`get-results` handles all databoxes from a channel and create a vector which contains all values of databoxes.
If a failure databox is found while handling databoxes, `get-results` will throw the exception and
handle all remaining data in a channel in background for protecting the channel from stacking caused by
never-read data in a channel.

`get-results` accepts arguments:

ch - a channel for reading.
option-map - optional. a map containing the following keys.

keys:
:catch - is a funciton called when an exception occurs. This fn is for closing related channels certainly.
   In many cases, if an exception occurred, no following channel-processsings are not acceptable,
   So all read channel must be closed at this time. It is recommended to supply this function always,
   but should not be used for handling application exceptions. Only for channel handling.
   Application exceptions should be handled by try-catch in application code which wraps this 'get-result' call.

:finally - is a function that will be called always, but be called after the ch is CLOSED.
   If the ch is not read fully, it will be read fully automatically.
   When the ch is read fully or be closed manually, this :finally fn will be called.
   So SHOULD NOT DO APPLICATION's FINALLY-PROCESS here. This function is for actions which must be occurred after
   the ch is closed. Application's finally-process must be handled by try-catch in application code which
   wraps this 'get-result' call.

:context-name - optional. a information which is used at logging for distincting processes.

:timeout-ms - optional. default is 120000 (ms). The time to give up reading the ch. An exception will be thrown after
   this timeout and the exception will be wrapped by a failure box and be returned.



### Connecting channels

You should call `get-results` at last for safe processing of channels, but before calling it, you can connect
the channel contained by a job to another channels. Although you can do it with `pipe` of core.async,
but there is a safe utility function for doing it.

(chain channel transducer exception-handler)

You can apply a transducer to a channel by `chain` function (with an exception handler if you want) and can get a next channel.
You can chain the calling of `chain` fns like:

```clojure
(-> (:channel job)
    (chain xf1)
    (chain xf2)
    (chain xf3))
```

This will return a last channel and you can get the results by calling `get-results` on the last channel.

Difference of `chain` and `pipe` is that `pipe` stops reading from an input channel if the output channel is closed,
but `chain` never stop reading input so that data never remain in an input channel.
Remaining data in a channel might cause accidental channel-stacking. All data should be read fully.

And `chain` is callable on all instances that satisfies the `Chainable` protocol. Currently channels of core.async and ConcurrentJob made by the `concurrently` function satisfy the protocol. So you can:

```clojure
;; You can apply a transducer to a ConcurrentJob.
;; The transducer will be applied to the :channel of the ConcurrentJob.
(let [job (-> (concurrently ...)
              (chain xf)]
  ..do something..)
```

`chain` always returns a same type of object that is passed to the `chain` function. So if you call `chain` on a channel, a channel will be returned, if you call `chain` on a ConcurrentJob, a ConcurrentJob will be returned.

Note that the data from a channel in a job always are databoxes. Transducer supplied to `chain` must handle
databox and must return databox. Use `databox.core/map`, `databox.core/mapcat` or `databox.core/filter` transducers
for handling databoxes for safe.
Functions of databox safely handle exceptions occurred in databox-processing and always return a databox.


### WORK THROUGH

```clojure
(defn my-great-function
  [data options]
  ;; do something....
  )

;;; create an engine handle data by 8 threads in parallel.
(def shared-process (concurrent-process-blocking 8
                                                 (chan 1)
                                                 ;; transducer must accept a map with :data and :options keys
                                                 (map #(let [{:keys [data options]}] (my-great-function data options))
                                                 (chan 1)))

;;; pass data
(let [{:keys [channel] :as job} (-> (concurrenty shared-process (to-chan [:a :b :c]) {:option-to-function true})
                                    (chain (databox.core/map #(upper-case %))))
      results (get-results channel
                           {:catch (fn [ex] (cancel job))
                            :finally (fn [] ...)
                            :timeout-ms 5000})]
  ;; you can handle results here
  )
```

## License

Copyright © 2019 Tsutomu YANO

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
