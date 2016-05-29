(ns postal.server.handlers
  (:require [catacumba.core :as ct]
            [catacumba.http :as http]
            [catacumba.handlers.parse :as parsing]
            [catacumba.handlers.auth :as auth]
            [catacumba.handlers.postal :as pc]
            [clojure.core.async :refer [<! >! alts! chan put! timeout go go-loop close!] :as async]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [buddy.sign.jwt :as jwt]
            [pusher.core :as p]
            [cheshire.core :as json]
            [environ.core :as e]
            [provisdom.stormpath.core :as sp]
            [provisdom.stormpath.auth :as sa]
            [provisdom.stormpath.account :as acc]
            [postal.server.util :as u])
  (:import (ratpack.http TypedData)
           (ratpack.handling Context)))

;;===================================
;; INIT
;;===================================
(def pusher-creds {:app-id     (e/env :pusher-app-id)
                   :api-key    (e/env :pusher-key)
                   :api-secret (e/env :pusher-secret)})

(defonce client (sp/client {:id (e/env :stormpath-id) :secret (e/env :stormpath-secret)}))
(defonce application (sp/application client (e/env :stormpath-app-name)))

(defonce pusher (p/pusher pusher-creds true))

(def ^:private secret (e/env :jwt-secret))
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
      (http/ok (json/encode
                 {:token (jwt/sign {:username        username
                                    :message-channel (channel-name-for username)
                                    :roles           (acc/get-group-names account)} secret)}))
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
      (do
        (println "GET PUSHER" identity)
        (http/ok (json/encode {:message-channel (:message-channel identity)
                               :api-key         (:api-key pusher-creds)})
                 {"Content-Type" "application/json"}))
      (http/unauthorized "Not authorized"))))

(defn post-message
  [ctx]
  (let [data (:data ctx)
        identity (:identity ctx)]
    (if identity
      (if (:body data)
        (let [resp (p/push! pusher (:message-channel identity) "messages" (walk/stringify-keys (:body data)) (:socket_id data))]
          (if (= (:status resp) 200)
            (do
              (println "PUSHED" data)
              (http/ok (json/encode {:message "Pushed message!"})
                       {"Content-Type" "application/json"}))
            (do
              (println "FAILED PUSH " (:message resp))
              (http/response (:message resp) (:status resp)))))
        (http/bad-request (str "Bad message: " data)))
      (http/unauthorized "Not authorized"))))