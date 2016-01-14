(ns postal.server.jwt
  (:require [buddy.sign.jws :as jws]
            [clj-time.core :refer [now plus days before?]]
            [clj-time.coerce :as c]))

(defn- payload-for
  [claims]
  (let [base {:exp (c/to-long (plus (now) (days 1)))
              :iat (c/to-long (now))}]
    (merge base claims)))

(defn sign
  [claims secret]
  (-> claims payload-for (jws/sign secret)))

(defn expired?
  [jwt]
  (-> jwt :exp c/from-long (before? (now))))