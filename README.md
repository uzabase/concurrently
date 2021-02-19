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
[concurrently "0.2.0"]
```

Clojure CLI:
```
concurrently/concurrently {:mvn/version "0.2.0"}
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

### getting calculated results from Job

`concurrently` returns a `Job`. You can cancel the job by calling `(cancel job)` function.
`Job` contains a field `:channel`. You can read all calculated results for all input data supplied to
`concurrently` function from the channel, but should not read it directly. 
Use `(get-results (:channel job))` function for safe-reading from channels.

`get-results` handles all databoxes from a channel and create a result vector. 
If a failure databox is found while handling databoxes, `get-results` will throw the exception and 
handle all remaining data in a channel in background for protecting the channel from stacking caused by 
never-read data in a channel.


### Connecting channels

You should call `get-results` at last for safe processing of channels, but before calling it, you can connect
the channel contained by a job to another channels. Although you can do it with `pipe` of core.async, 
but there is a safe utility function for doing it.

(chain channel transducer exception-handler)

You can connect a channel to a transducer by this `chain` function (with an exception handler if you want) and can get a next channel.
This connection can be chained like:

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
(let [{:keys [channel] :as job} (concurrenty shared-process (to-chan [:a :b :c]) {:option-to-function true})
      next-ch (chain channel (databox.core/map #(upper-case %)))
      results (get-results next-ch 
                           {:catch (fn [ex] ...)
                            :finally (fn [] ...)
                            :timeout-ms 5000})]
  ;; you can handle results here
  )
```

## License

Copyright © 2019-2020 Tsutomu YANO

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
