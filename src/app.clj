(ns app
  (:require
   [clojure.pprint :refer [pprint]]
   [charred.api]
   [dev.onionpancakes.chassis.core :as c :refer [html]]
   [dev.onionpancakes.chassis.compiler :as cc]
   [reitit.ring :as rr]
   [reitit.core :as r]
   [reitit.ring.middleware.parameters :as rmp]
   [ring.util.response :as ruresp]
   [org.httpkit.server :as hks]
   [ring.util.response]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.http-kit :as hk-gen :refer [->sse-response]]
   [lambdaisland.faker :as fl :refer [fake]]))

(def read-json (charred.api/parse-json-fn {:async? false :bufsize 1024 :key-fn keyword}))

(let [!count (atom 0)]
  (defn new-content
    "Generate new content"
    []
    (str (swap! !count inc) ": " (fake [:hitchhikers-guide-to-the-galaxy :marvin-quote]))))

;connections are associated with tabid
;when the tab is eventualy closed tab specific state will have to be disposed.
(def !connections (atom #{}))

(defn broadcast [request f & args]
  (println ::broadcast)
  (doseq [sse-gen @!connections]
    (println "send to: " (.hashCode sse-gen))
    (apply f sse-gen args)))

(defn with-open-sse
  "open send and close sse"
  [request f & args]
  (->sse-response request
                  {hk-gen/on-open (fn [sse-gen]
                                    (d*/with-open-sse sse-gen
                                      (apply f sse-gen args)))}))

(defn unique-pane [content]
  [:label#unique content])

(defn shared-pane [content]
  [:label#shared content])

(defn view [request]
  [:div#view
   ;[:div [:label "tabid: "] [:label {:data-text "$tabid"}]]
   [:div
    [:label.btn {:data-on-click (d*/sse-patch "/cmd/update-this")} "Update this tab"]
    (unique-pane "")]
   [:div
    [:label.btn {:data-on-click (d*/sse-patch "/cmd/update-all")} "Update all tabs"]
    (shared-pane "")]])

(defn page
  [request]
  (cc/compile
   [c/doctype-html5
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title "httpkit-test"]
      [:script {:src d*/CDN-url :type "module"}]
      [:link {:rel "stylesheet" :href "/css/main.css"}]]
     [:body.p-8 {:data-on-load (d*/sse-get "/connect")}
      #_{:data-on-load (d*/sse-get "/connect")
       ;:data-on-signal-patch-filter "{include: /^tabid$/}";regex
       ;request: /cmd/init when tabid is set
       ;:data-on-signal-patch (d*/sse-patch "/cmd/init")
       ;generate a tab specific ID that will persist through a page refresh.
       :data-computed-tabid "(sessionStorage.getItem('tabId') ||
                               sessionStorage.setItem('tabId', crypto.randomUUID()) ||
                               sessionStorage.getItem('tabId'))"}
      [:div.mx-auto.max-w-7xl.sm:px-6.lg:px-8
       (view request)]]]]))

(defn connect-handler [request]
  (->sse-response request
                  {hk-gen/on-open
                   (fn [sse-gen]
                     (swap! !connections conj sse-gen)
                     (d*/patch-elements! sse-gen (-> request view html))
                     ;(d*/console-log! sse-gen (str "connected: " (.hashCode sse-gen)))
                     (println "connected: " (.hashCode sse-gen))) ;cache sse connection
                   hk-gen/on-close
                   (fn on-close [sse-gen status-code]
                     (swap! !connections disj sse-gen)
                     (println (format "SSE Disconnected %s; status: %s" (.hashCode sse-gen) status-code)))}))

(defn index [request]
  ;Render the initial page.
  (tap> ::index)
  (-> request page html ruresp/response (ruresp/content-type "text/html")))

(defn on-update-this [request]
  (with-open-sse request d*/patch-elements! (-> (new-content) unique-pane html)))

(defn on-update-all [request]
  (broadcast request d*/patch-elements! (-> (new-content) shared-pane html))
  {:status 204})

(defn on-init [request]
  ;Called after the initial page render and we have a tabid signal.
  ;tabid is used as a key to persist state and a connection.
  ;(on-update-all request)
  ;(connect-handler request)
  )

(defn cmd-handler [request]
  (let [{:keys [cmd]} (get-in request [::r/match :path-params])
        request (assoc request :signals (some-> request d*/get-signals read-json))]
    (println (format "cmd: %s, from tabid: %s" cmd (get-in request [:signals :tabid])))
    ((case (keyword cmd)
       :init on-init
       :update-this on-update-this
       :update-all on-update-all) request)))

(def routes
  [["/" {:get  #'index}]
   ["/connect" #'connect-handler]
   ["/cmd/:cmd" #'cmd-handler]
   ])

(defonce !server (atom nil))

(defn stop! []
  (when-let [s @!server]
    (hks/server-stop! s)
    (reset! !server nil)))

(defn start! [opts]
  (stop!)
  (reset! !connections #{})
  (let [port (or (:port opts) 8080)
        middleware [rmp/parameters-middleware]
        router (rr/router routes {:data {:middleware middleware}})
        handler (rr/ring-handler
                 router
                 (rr/routes
                  (rr/create-resource-handler {:path "/"})
                  (rr/create-default-handler))
                 ;{:middleware hk2/start-responding-middleware} ;when using http-kit2 adapter
                 )]
    (reset! !server
            (hks/run-server
             handler
             (merge {:port port
                     :legacy-return-value? false}
                    opts)))
    (println (str "server running on: http://localhost:" port \/))))

(defonce _shutdown (.addShutdownHook (Runtime/getRuntime)
                                     (Thread. #(do (stop!) (shutdown-agents)))))

(comment
  (stop!)
  (start! {:port 8085})
  @!connections
  (reset! !connections #{})
  ;
  )

(defn -main [& _]
  (start! {:port 8085}))

