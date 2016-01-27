(ns postal.server.handlers
  (:require [catacumba.http :as http]
            [catacumba.handlers.parse :as parsing]
            [catacumba.handlers.auth :as auth]
            [catacumba.handlers.postal :as pc]
            [catacumba.handlers.postal :as pc]
            [clojure.core.async :refer [<! >! alts! chan put! timeout go go-loop close!] :as async]
            [clojure.edn :as edn]
            [pubnub :as p]
            [buddy.sign.jws :as jws]
            [postal.server.jwt :as jwt]
            [postal.server.util :as u]
            [pro.stormpath.core :as sp]
            [pro.stormpath.auth :as sa]
            [pro.stormpath.account :as acc]
            [catacumba.core :as ct]
            [cheshire.core :refer [parse-string]])
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

;;TODO: Replace with SQS
(def message-relay (chan 100))

(def creds {:access-key (System/getenv "AWS_ACCESS_KEY_ID")
            :secret-key (System/getenv "AWS_SECRET_ACCESS_KEY")})

(def pubnub-creds {:subscribe-key (System/getenv "SUBSCRIBE_KEY")
                   :publish-key   (System/getenv "PUBLISH_KEY")})

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
      (http/ok (jwt/sign {:username username
                          :roles    (acc/get-group-names account)} secret))
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

(defn- channel-for
  [user]
  (-> {:channel user} (merge pubnub-creds) p/channel))

(defn- send-message
  [ch msg]
  (go (>! ch (pc/frame {:value msg}))))

(defmethod postal-handler {:type :socket
                           :dest :messages}
  [ctx frame]
  (letfn [(on-connect [{:keys [in out ctrl] :as context}]
            (println "connect")
            ;; TODO: Make all async
            (ws-auth
              frame
              (fn [identity]
                (let [channel (channel-for (:username identity))
                      subscription (p/subscribe channel)]
                  (go-loop []
                    (let [[v p] (alts! [ctrl in subscription])]
                      (cond
                        ;; RECEIVE FROM CLIENT
                        (= p in)
                        (do
                          (println "IN" v)
                          (when (= (:data v) "close")
                            (println "closed")
                            (close! out))
                          (recur))

                        ;; RECIEVE FROM PUBNUB
                        (= p subscription)
                        (do
                          (println "v" v)
                          (when (= (:status v) :success)
                            (send-message out (:payload v)))
                          (recur))

                        ;; CLOSE
                        (= p ctrl)
                        (do
                          (println "Channel closed")
                          #_(sqs/delete-queue queue-url))))))
                (go
                  (>! out (pc/frame {:value "test"}))))
              (fn [err-msg]
                (go
                  (>! out (pc/frame {:value "Unauthorized"}))
                  (close! out)))))]
    (pc/socket ctx on-connect)))