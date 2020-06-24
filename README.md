# concurrently

A clojure library for making concurrent process-engine backed by core.async.
Application can share the engine and put process-requests onto the engine.
Engine will return a channel containing all processed results for the requests.

This library safely handle pipeline backed by core.async so that pipeline never
stack by abuse of channels like stopping data from channels or never closing channels.

This library backed by databox (https://github.com/tyano/databox) that wrap data in
boxes and make exception-safe handling data in channel-pipeline. All exceptions 
occured in databoxes safely are wrapped and be pass-through channels until the end
of channel-pipeline.

`get-results` function will handle all actions needed for protecting pipeline from
accidental stacking. `get-results` try to retrieve all data from an output channel 
and unwrap databoxes. If an exception were occurred in pipeline, databoxes will throw
the exception, but `get-results` will handle all data remaining in channel-pipeline for
protecting the pipeline from accidental stacking.

## Usage

### Create an process-engine by concurrent-process-blocking

You can make an engine from a function with input and output channels and parallel count by
calling the `concurrent-process-blocking` function.
All data supplied into input-channel will be handled by a supplied function in parallel by threads
same number of parallel count. But you should not put data into the input-channel directly.
You can supply data into an engine by another function.

`concurrent-process-blocking` will return a process-context. 
You can use the context for supplying data for the engine by passing it to `concurrently` function.


### Supply data for the created engine 

You can pass a channel containing input-data for the engine to `concurrently` function. The function
will return a `concurrent-job`. You can get the handled results by calling `(get-results (:channel job))` function.
Or cancel the job by calling `(cancel job)` function.

### Connecting channels

You should call `get-results` at last for safe processing of channels, but before calling it, you can connect
the channel containing by a job to another channels. You can do it with `pipe` of core.async, but there is a utility
function for doing it. 

(chain channel transducer exception-handler)

You can connect a channel to a transducer by this function (with an exception handler if you want) and can get a next channel.
This connection can be chained like:

```clojure
(-> channel
    (chain xf1)
    (chain xf2)
    (chain xf3))
```
    
This will return a last channel and you can get the results by calling `get-results` on the last channel.


### WORK THROUGH

(defn my-great-function
  [data options]
  ;; do something....
  )

;;; create an engine handle data by 8 threads in parallel.
(def shared-process (concurrent-process-blocking 8 (chan 1) my-great-function (chan 1)))
  
;;; pass data
(let [{:keys [channel] :as job} (concurrenty shared-process (to-chan [:a :b :c]) {})
      results (get-results channel 
                           {:catch (fn [ex] ...)
                            :finally (fn [] ...)
                            :timeout-ms 5000})]
  ;; you can handle results here
  )



## License

Copyright © 2019 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
