(ns postal.server.util
  (:require [clojure.string :as str]))

(def basedir (System/getenv "BASEDIR"))

(defn alpha-numberic
  [str]
  (str/replace str #"[^a-zA-Z0-9]" ""))