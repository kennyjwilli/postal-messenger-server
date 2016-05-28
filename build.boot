(def project 'postal/server)
(def version "0.1.0-SNAPSHOT")

(set-env!
  :resource-paths #{"src" "resources"}
  :dependencies '[[provisdom/boot-tasks "0.6.0" :scope "test" :exclusions [commons-codec]]
                  [org.clojure/clojure "1.7.0"]
                  [org.clojure/clojurescript "1.7.170"]
                  [org.clojure/core.async "0.2.374"]
                  [cljs-http "0.1.39"]
                  [funcool/catacumba "0.11.0"]
                  [funcool/promesa "0.7.0"]
                  [funcool/httpurr "0.3.0"]
                  [buddy/buddy-sign "0.9.0"]
                  [clj-time "0.11.0"]
                  [danlentz/clj-uuid "0.1.6"]
                  [cheshire "5.5.0" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                  [com.cemerick/url "0.1.1"]
                  [provisdom/stormpath "0.2.0" :exclusions [org.slf4j/slf4j-log4j12
                                                            log4j/log4j]]
                  [pusher "0.1.1"]])

(require
  '[provisdom.boot-tasks.core :refer :all])

(task-options!
  pom {:project     project
       :version     version
       :description "The Postal Messenger server"
       :url         "https://github.com/kennyjwilli/postal-messenger-server"
       :license     {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}})