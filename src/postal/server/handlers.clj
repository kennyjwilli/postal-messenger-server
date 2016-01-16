(ns postal.server.handlers
  (:require [catacumba.http :as http]
            [catacumba.handlers.parse :as parsing]
            [catacumba.handlers.auth :as auth]
            [catacumba.handlers.postal :as pc]
            [catacumba.handlers.postal :as pc]
            [clojure.core.async :refer [<! >! alts! chan put! timeout go go-loop close!] :as a]
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
            [clj-uuid :as uuid]
            [cheshire.core :refer [parse-string]]
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

(defn random-queue-name-for
  [username]
  (str (u/alpha-numberic username) "-" (uuid/v1)))

(defn sqs-queue-for!
  [username sns-arn]
  (let [queue-name (random-queue-name-for username)]
    (sqs/create-queue :queue-name queue-name
                      :attributes {:Policy (aws/allow-sns-push-to-sqs-policy (aws/sqs-arn queue-name) sns-arn)})))

(defn sqs-message-chan
  ([url timeout]
   (let [chan (chan 10)]
     (go-loop []
       (when (<! chan)
         (let [messages (:messages (sqs/receive-message :queue-url url
                                                        :max-number-of-messages 1
                                                        :delete true))]
           (when-not (empty? messages)
             (doseq [msg messages]
               (>! chan (parse-string (:body msg) true)))))
         (<! (a/timeout timeout))
         (recur)))
     chan))
  ([url] (sqs-message-chan url 500)))

(defmethod postal-handler {:type :socket
                           :dest :messages}
  [ctx frame]
  (letfn [(on-connect [{:keys [in out ctrl] :as context}]
            (println "connect")
            (ws-auth
              frame
              (fn [identity]
                (let [topic-arn (:topic-arn identity)
                      queue-url (-> identity :username (sqs-queue-for! topic-arn) :queue-url) ;TODO: Normalize identity
                      queue-arn (-> queue-url sqs/get-queue-attributes :QueueArn)
                      sqs-chan (sqs-message-chan queue-url)]
                  (println "CREATE SQS" queue-arn)
                  (sns/subscribe :topic-arn topic-arn
                                 :protocol "sqs"
                                 :endpoint queue-arn)
                  (println "SUBSCRIBED")
                  (go-loop []
                    (let [[v p] (alts! [ctrl in sqs-chan])]
                      (cond
                        ;; RECEIVE
                        (= p in)
                        (do
                          (println "IN" v)
                          (when (= (:data v) "close")
                            (println "closed")
                            (close! out))
                          (recur))

                        ;; RECEIVE FROM SQS
                        (= p sqs-chan)
                        (do
                          (println "SQS" v)
                          (recur))

                        ;; CLOSE
                        (= p ctrl)
                        (do
                          (println "Channel closed")
                          (sqs/delete-queue queue-url))))))
                (go
                  (>! out (pc/frame {:value "test"}))))
              (fn [err-msg]
                (go
                  (>! out (pc/frame {:value "Unauthorized"}))
                  (close! out)))))]
    (pc/socket ctx on-connect)))