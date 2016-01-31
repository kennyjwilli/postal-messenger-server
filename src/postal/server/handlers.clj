(ns postal.server.handlers
  (:require [catacumba.http :as http]
            [catacumba.handlers.parse :as parsing]
            [catacumba.handlers.auth :as auth]
            [catacumba.handlers.postal :as pc]
            [catacumba.handlers.postal :as pc]
            [clojure.core.async :refer [<! >! alts! chan put! timeout go go-loop close!] :as async]
            [clojure.edn :as edn]
            [buddy.sign.jws :as jws]
            [postal.server.jwt :as jwt]
            [postal.server.util :as u]
            [pro.stormpath.core :as sp]
            [pro.stormpath.auth :as sa]
            [pro.stormpath.account :as acc]
            [pusher.core :as p]
            [catacumba.core :as ct]
            [cheshire.core :refer [parse-string generate-string]])
  (:import (ratpack.http TypedData)
           (ratpack.handling Context)))

;;===================================
;; INIT
;;===================================
(def pusher-creds {:app-id     (System/getenv "APP_ID")
                   :api-key    (System/getenv "API_KEY")
                   :api-secret (System/getenv "API_SECRET")})

(defonce api-key (sp/build-api-key sp/path))
(defonce client (sp/build-client api-key))
(defonce tenant (sp/get-tenant client))
(defonce application (sp/get-tenant-application tenant "Postal Messenger"))

(defonce pusher (p/pusher pusher-creds))

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

(defn channel-name-for
  [username]
  (str "private-message-" username))

(defn login-handler
  [ctx]
  (let [params (:data ctx)
        username (or (:username params) (:email params))
        password (:password params)
        auth-result (sa/do-auth application username password)
        account (:account auth-result)]
    (if (:success auth-result)
      ;; TODO: Should use JWE?
      (http/ok (jwt/sign {:username        username
                          :message-channel (channel-name-for username)
                          :roles           (acc/get-group-names account)} secret))
      (http/unauthorized "Incorrect login"))))

(defn pusher-auth
  "Authorizes a user to subscribe to a specific pusher message channel"
  [ctx]
  (let [data (:data ctx)
        message-channel (get-in ctx [:identity :message-channel])]
    (if (= message-channel (:channel_name data))
      (http/ok (p/authenticate pusher (:socket_id data) (:channel_name data)))
      (http/unauthorized (str "You do not have permission to subscribe to " (:channel_name data))))))

(defn get-pusher
  [ctx]
  (let [identity (:identity ctx)]
    (if identity
      (http/ok (generate-string {:message-channel (:message-channel identity)
                                 :api-key         (:api-key pusher-creds)})
               {"Content-Type" "application/json"})
      (http/unauthorized "Not authorized"))))

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
                (let []
                  (go-loop []
                    (let [[v p] (alts! [ctrl in])]
                      (cond
                        ;; RECEIVE FROM CLIENT
                        (= p in)
                        (do
                          (println "IN" v)
                          (when (= (:data v) "close")
                            (println "closed")
                            (close! out))
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