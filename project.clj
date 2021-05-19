(defproject concurrently "0.2.1"
  :description "A clojure library for running actions concurrently but restricting a number of threads."
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.2"]
                 [org.clojure/core.async "0.4.500"]
                 [org.clojure/tools.logging "0.5.0"]
                 [databox "0.1.7"]]
  :profiles {:dev {:dependencies [[eftest "0.5.9"]]
                   :plugins [[lein-eftest "0.5.9"]]}}
  :repl-options {:init-ns concurrently.core})
