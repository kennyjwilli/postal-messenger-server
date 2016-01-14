(ns postal.server.handlers
  (:require [catacumba.http :as http]
            [catacumba.handlers.parse :as parsing]
            [catacumba.handlers.auth :as auth]
            [catacumba.handlers.postal :as pc]
            [catacumba.handlers.postal :as pc]
            [clojure.core.async :refer [<! >! chan put! timeout go go-loop close!]]
            [clojure.edn :as edn]
            [amazonica.aws.sns :as sns]
            [amazonica.aws.sqs :as sqs]
            [buddy.sign.jws :as jws]
            [postal.server.jwt :as jwt]
            [postal.server.util :as u]
            [pro.stormpath.core :as sp]
            [pro.stormpath.auth :as sa]
            [pro.stormpath.account :as acc]
            [catacumba.core :as ct]
            [postal.server.aws :as aws])
  (:import (ratpack.http TypedData)
           (ratpack.handling Context)))

;;===================================
;; INIT
;;===================================
(defonce api-key (sp/build-api-key sp/path))
(defonce client (sp/build-client api-key))
(defonce tenant (sp/get-tenant client))
(defonce application (sp/get-tenant-application tenant "Postal Messenger"))

(def ^:private secret (System/getenv "JWT_RSA_PRV_PASS"))
(def auth-backend
  (auth/jws-backend {:secret secret}))

;;===================================
;; PARSING
;;===================================
(defmethod parsing/parse-body :application/edn
  [^Context ctx ^TypedData body]
  (let [^String data (slurp body)]
    (edn/read-string data)))

;;===================================
;; HANDLERS
;;===================================
(defn my-error-handler
  [context error]
  (http/internal-server-error (.getMessage error)))

(defn require-auth
  [ctx]
  (println ctx)
  (if (:identity ctx)
    (ct/delegate)
    (http/found "You need to login")))

(defn login-handler
  [ctx]
  (let [params (:data ctx)
        username (or (:username params) (:email params))
        password (:password params)
        auth-result (sa/do-auth application username password)
        account (:account auth-result)]
    (if (:success auth-result)
      ;; TODO: Should use JWE?
      (http/ok (jwt/sign {:username  username
                          :topic-arn (:topic-arn (sns/create-topic (u/alpha-numberic username)))
                          :roles     (acc/get-group-names account)} secret))
      (http/unauthorized "Incorrect login"))))

(defmulti postal-handler
          (fn [ctx frame] (select-keys frame [:type :dest])))

(defn ws-auth
  [frame success-fn error-fn]
  (let [jwt (get-in frame [:data :token])]
    (if jwt
      (let [decoded (jws/unsign jwt secret)]
        (if (jwt/expired? decoded)
          (error-fn "Expired token")
          (success-fn decoded)))
      (error-fn "No token"))))

(defmethod postal-handler {:type :socket
                           :dest :messages}
  [ctx frame]
  ;; TODO: Error handling is a little funky
  (ws-auth
    frame
    (fn [identity]
      (letfn [(on-connect [{:keys [in out] :as context}]
                (println "connect")
                (sns/subscribe :topic-arn (:topic-arn identity))
                (go
                  (>! out (pc/frame {:value "test"})))
                (go-loop []
                  (when-let [value (<! in)]
                    (println "IN" value)
                    (recur))))]
        (pc/socket ctx on-connect)))
    (fn [err-msg]
      (http/unauthorized err-msg))))