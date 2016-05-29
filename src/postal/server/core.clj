(ns postal.server.core
  (:require [catacumba.core :as ct]
            [catacumba.handlers.misc :as misc]
            [catacumba.handlers.auth :as auth-handlers]
            [catacumba.handlers.parse :as parsing]
            [catacumba.handlers.postal :as pc]
            [postal.server.handlers :as handlers]
            [postal.server.util :as u]
            [catacumba.http :as http])
  (:gen-class))


(def routes
  (ct/routes
    [[:any (misc/autoreloader)]
     [:error #'handlers/my-error-handler]
     ;; TODO: Add permission guard and check token exp
     [:any (auth-handlers/auth handlers/auth-backend)]
     [:any (parsing/body-params)]
     [:assets "" {:dir     "target"
                  :indexes ["index.html"]}]
     [:post "login" #'handlers/login-handler]
     [:post "foo" (fn [ctx] (println (:identity ctx)) (http/ok "cool"))]
     [:prefix "api"
      [:get "pusher" #'handlers/get-pusher]
      [:post "pusher-auth" #'handlers/pusher-auth]
      [:post "message" #'handlers/post-message]]]))

(comment
  ;;; TODO - put in -main
  (def srvr (ct/run-server routes {:basedir u/basedir
                                   :debug   true
                                   :port    8080}))
  (.stop srvr)
  )

(defn -main [& args]
  (ct/run-server routes {:basedir u/basedir
                         :debug   true
                         :port    80}))