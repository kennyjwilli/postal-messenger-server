(set-env!
  :resource-paths #{"src" "resources" "config"}
  :repositories [["clojars" "http://clojars.org/repo/"]
                 ["maven-central" "http://repo1.maven.org/maven2/"]
                 ["my.datomic.com" {:url      "https://my.datomic.com/repo"
                                    :username (System/getenv "DATOMIC_USERNAME")
                                    :password (System/getenv "DATOMIC_PASSWORD")}]]

  :dependencies '[[provisdom/boot-tasks "0.4.0" :scope "test" :exclusions [commons-codec]]])

(require
  '[provisdom.boot-tasks :refer :all])

(set-project-deps!)
(default-task-options!)