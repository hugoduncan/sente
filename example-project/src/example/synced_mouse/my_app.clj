(ns example.synced-mouse.my-app
  "A Sente client+server supplementary web-app example.
  Avoids Cljx for easier use with LightTable.

  ------------------------------------------------------------------------------
  This is an unofficial example graciously provided by @tf0054. It's included
  as-is for folks that might find it useful. Please see the `basics` example for
  an alternative + actively-maintained reference. - Peter Taoussanis
  ------------------------------------------------------------------------------

  INSTRUCTIONS:
    * For starting http-server, eval with your LightTable REPL: `(-main)`.
    * For stopping http-server, eval with your LightTable REPL: `(stop-server)`."

  {:author "Takeshi NAKANO (@tf0054)"}
  (:require [ring.util.response :refer [file-response]]
            [ring.middleware.edn :refer [wrap-edn-params]]
            [ring.middleware.session :as ringsession]
            [ring.middleware.anti-forgery :as ring-anti-forgery]
            [compojure.core :refer [defroutes GET PUT POST]]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [org.httpkit.server :as http-kit-server]
            [clojure.core.match :as match :refer (match)]
            [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop)]
            [taoensso.timbre    :as timbre]
            [taoensso.sente     :as sente]
            ))

(defn- logf [fmt & xs] (println (apply format fmt xs)))

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket!
        {:send-buf-ms-ws 10 ; Large buffer will cause awkward mouse movement
         })]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv)
  (def chsk-send!                    send-fn)
  (def connected-uids                connected-uids))

;;;;

(defn broadcast-mouse [{:keys [uid x y]} type]
  (doseq [z (:any @connected-uids)]
    (when-not (= z uid)
      (match [type]
        ["over"] (chsk-send! z [:mouse/show      {:from uid}])
        ["out"]  (chsk-send! z [:mouse/clear     {:from uid}])
        ["move"] (chsk-send! z [:mouse/broadcast {:from uid :x x :y y}])))))

(defn- event-msg-handler
  [{:as ev-msg :keys [ring-req event ?reply-fn]} _]
  (let [session (:session ring-req)
        uid     (:uid session)
        [id data :as ev] event]
    (match [id data]
      [:mouse/position data] (do (logf "event(:mouse/position): %s" data)
                                 (broadcast-mouse data "move"))
      [:mouse/clear data]    (do (logf "event(:mouse/clear): %s" data)
                                 (broadcast-mouse data "out"))
      [:mouse/show data]     (do (logf "event(:mouse/show): %s" data)
                                 (broadcast-mouse data "over"))
      :else
      (do (logf "Unmatched event: %s" ev)
          (when-not (:dummy-reply-fn? (meta ?reply-fn))
            (?reply-fn {:umatched-event-as-echoed-from-from-server ev}))))))

(defonce chsk-router (sente/start-chsk-router-loop! event-msg-handler ch-chsk))

;;;;

(defn index [req]
  (let [uid (or (-> req :session :uid) (rand-int 999))
        res (file-response "public/html/index.html" {:root "resources"})]
    (logf "Session: %s" (:session req))
    (assoc res :session {:uid uid})))

(defroutes routes
  (GET "/" req (index req))
  (route/files "/" {:root "resources/public"}) ; For css/js
  ;;
  (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post                req)))

(def my-ring-handler (-> routes ringsession/wrap-session))

(defonce http-server (atom nil)) ; Await manual eval
(defn run-http-server []
  (let [s   (http-kit-server/run-server (var my-ring-handler) {:port 0})
        uri (format "http://localhost:%s/" (:local-port (meta s)))]
    (logf "Http-kit server is running at `%s`" uri)
    (.browse (java.awt.Desktop/getDesktop)
             (java.net.URI. uri))
    (reset! http-server s)))

(defn stop-http-server []
  (when @http-server
    ;; Graceful shutdown: wait 100ms for existing requests to be finished.
    ;; :timeout is optional, when no timeout, stop immediately
    (@http-server :timeout 100)
    (reset! http-server nil)))

(defn -main "For running server with `lein run`." [] (run-http-server))

(comment (-main))
