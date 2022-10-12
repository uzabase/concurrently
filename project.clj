(defproject concurrently "0.2.3"
  :description "A clojure library for running actions concurrently but restricting a number of threads."
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/core.async "1.3.622"]
                 [org.clojure/tools.logging "1.1.0"]
                 [databox "0.1.7"]]
  :profiles {:dev {:dependencies [[eftest "0.5.9"]
                                  [com.taoensso/timbre "5.1.2"]
                                  [com.fzakaria/slf4j-timbre "0.3.21"]]
                   :plugins [[lein-eftest "0.5.9"]]
                   :source-paths ["dev/src"]
                   :resource-paths ["dev/resources"]}}
  :repl-options {:init-ns concurrently.core})
