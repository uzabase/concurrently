# concurrently

A clojure library for making concurrent process-engine backed by core.async.
Application can share the engine and can put requests onto the engine.
Engine will return a channel containing all processed results for each request.

All verbose processes for splitting or collecting results for each request and
putting the results onto a correct channel are handled by this library.
All things that programmer must do is 1) creating a shared pipeline,
2) put all data you want to process onto the input-channel of the shared pipeline,
3) handle the results by calling `get-results` on the output-channel returned by
the shared pipeline.

This library safely handles pipelines backed by core.async so that pipelines never
stack by abuse of channels like stopping data-retrieving from channels before
fully retrieving all data or forgetting to close channels.

This library is backed by databox (https://github.com/tyano/databox) that wraps data into
a box and make data-handling in channel-pipeline exception-safe. All exceptions
occured in databox safely are wrapped and be passed-through channels until the end
of channel-pipeline, so that users can handle exceptions at the end of pipeline.

`get-results` function handles all actions needed for protecting pipeline from
accidental stacking. `get-results` try to retrieve all data from an output-channel
and unwrap databoxes. If an exception were occurred in the pipeline,
unwrapping the failure-databox will throw the exception,
but `get-results` handles all data remaining in then pipeline for protecting it
from accidental stacking.

## Usage

### Install

Leiningen:
```
[concurrently "0.2.3"]
```

Clojure CLI:
```
concurrently/concurrently {:mvn/version "0.2.3"}
```

### Create an process-engine by `concurrent-process` function

You can make an engine by calling `concurrent-process` or `concurrent-process-blocking` functions
with a transducer, input channel, output channels and max parallel count.
All data supplied into then input-channel will be handled by a supplied transducer in parallel by
go-blocks or threads of same number with the parallel count.

But you should not put data into the input-channel directly. You must use the `concurrently` function
(Explanation of the `concurrently` fn is following).

Transducer must accept a map with :data and :options keys.
:data is a value from input channel.
:options is a option-map supplied to `concurrently` function.

example:
```clojure
(def shared-process (concurrent-process
                        8
                        (chan 1)
                        ;; transducer must accept a map with :data and :options keys
                        (map #(fn [{:keys [data options]}] (my-great-function data options))
                        (chan 1)))
```

### unordered pipeline

All of pipeline functions of core.async only support ordered pipeline, on which
all output data are produced in same order of input. But in real usecase you would
want to get results as soon as possible just after the input data is processed.

This library supports 'unordered-pipeline'.

With onordered pipeline, output data are not in same order with input values.
all results will be pushed to output channel as soon as possible after a parallel
task finished.

For making unordered pipeline, just supply {:ordered false} as option map to
`make-concurrent-process` function.

example:
```clojure
(def shared-process (concurrent-process
                        8
                        (chan 1)
                        ;; transducer must accept a map with :data and :options keys
                        (map #(fn [{:keys [data options]}] (my-great-function data options))
                        (chan 1)
                        {:ordered? false})) ;; <<- This line
```

### Supply data to the created engine

`concurrent-process` and `concurrent-process-blocking` functions return a process-context which contains
an input channel.
But you should not use the input channel directly.
Instead, you should use the `concurrently` function with arguments of the returned process-context,
a channel which all input data can be read from, and an option map.

`concurrently` wraps all input-data by `databox` and concurrent-process handles
the databoxes so that if some exceptions occurred in a concurrent-process, the exception will safely
be converted to failure-boxes and be passed-through the pipeline.

### getting calculated results from a Job

`concurrently` returns a `Job`. You can cancel the job by calling `(cancel job)` function.
`Job` contains a field `:channel`. You can read all calculated results for all input-data supplied to
`concurrently` function from the channel, but should not read it directly.
Use `(get-results (:channel job))` function for safe-reading from channels.

`get-results` handles all databoxes from a channel and create a vector which contains all values of databoxes.
If a failure databox is found while handling databoxes, `get-results` will throw the exception and
handle all remaining data in a channel in background for protecting the channel from accidental stacking caused by
never-read data in a channel.

`get-results` accepts arguments:

ch - a channel for reading.
option-map - optional. a map containing the following keys.

keys:
:catch - is a funciton called when an exception occurs. This fn is for closing related channels certainly.
   In many cases, if an exception occurred, no following channel-processsings are acceptable,
   So all read-channels must be closed at this time. It is recommended to supply this function always,
   but should not be used for handling application exceptions. Only for channel handling.
   Application exceptions should be handled in application code by a try-catch block which wraps this 'get-result' call.

:finally - is a function that will be called always, but be called after the ch is CLOSED.
   If the ch is not read fully, it will be read fully automatically.
   When the ch is read fully or be closed manually, this :finally fn will be called.
   So SHOULD NOT DO APPLICATION's FINALLY-PROCESS here. This function is for actions which must be occurred after
   the ch is closed. Application's finally-process must be handled in application code by a try-catch block which
   wraps this 'get-result' call.

:context-name - optional. a information which is used at logging for distincting processes.

:timeout-ms - optional. default is 120000 (ms). The time to give up reading the ch. An exception will be thrown after
   this timeout and the exception will be wrapped by a failure box and be returned.



### Connecting channels

You should call `get-results` at last for safe-processing of channels, but before calling it, you can connect
the job to other transducers. Although you can do it with `pipe` of core.async,
but there is a safe utility function for doing it.

(chain job-or-channel transducer exception-handler)

You can apply a transducer to a job-or-channel by `chain` function (with an exception handler if you want)
and can get a next job-or-channel. You can chain the calling of `chain` fns like:

```clojure
(-> job
    (chain xf1)
    (chain xf2)
    (chain xf3))
```

This will return a last job-or-channel and you can get the results by calling `get-results` on the last job-or-channel.

Difference of `chain` and `pipe` is that `pipe` stops reading from an input channel if the output channel is closed,
but `chain` never stop reading input so that data never remain in an input channel.
Remaining data in a channel might cause accidental channel-stacking. All data should be read fully.

You can use `chain` on all instances that satisfies the `Chainable` protocol. Currently channels of core.async and
ConcurrentJob returned from the `concurrently` function satisfy the protocol. So you can:

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
Because functions of databox safely handle exceptions that occurred while processing data and always return a databox,
and most of the functions construct transducers, you should use them for handling databoxes.

For example:

```clojjure
(let [job (-> (concurrently ...)
              (chain (databox.core/map #(your-great-mapper %))
              (chain (databox.core/filter #(your-great-filter %)))]
  ..do something..)
```


### WORK THROUGH

```clojure
(defn my-great-function
  [data options]
  ;; do something and return a string
  )

;;; create an engine handle data by 8 threads in parallel.
(def shared-process (concurrent-process-blocking 8
                                                 (chan 1)
                                                 ;; transducer must accept a map with :data and :options keys
                                                 (map #(fn [{:keys [data options]}] (my-great-function data options))
                                                 (chan 1)))

;;; pass data
(let [{:keys [channel] :as job} (-> (concurrenty shared-process (to-chan! [:a :b :c]) {:option-to-function true})
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
