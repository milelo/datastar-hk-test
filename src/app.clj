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

(def !new-content-count (atom 0))

(defn new-content
  "Generate new content"
  []
  (str (swap! !new-content-count inc) ": " (fake [:hitchhikers-guide-to-the-galaxy :marvin-quote])))

(def !tab-states (atom {}))

(def !shared-state (atom {}))

(defn broadcast [_request f & args]
  (doseq [[k state] @!tab-states
          :when (:sse-gen state)]
    (pprint (assoc (dissoc state :sse-gen) :tabid k :src :broadcast))
    (apply f (:sse-gen state) args)))

(defn tabid [request]
  (get-in request [:signals :tabid]))

(defn with-open-sse
  "open send and close sse"
  [request f & args]
  (->sse-response request
                  {hk-gen/on-open (fn [sse-gen]
                                    (d*/with-open-sse sse-gen
                                      (apply f sse-gen args)))}))

(defn unique-pane [request]
  ;there is no tabid on the initial page render
  [:label#unique (get-in @!tab-states [(tabid request) :unique-content])])

(defn shared-pane [_request]
  [:label#shared (get @!shared-state :shared-content)])

(defn view [request]
  [:div#view
   [:div [:label "tabid: "] [:label {:data-text "$tabid"}]]
   [:div
    [:label.btn {:data-on-click (d*/sse-patch "/cmd/update-this")} "Update this tab"]
    (unique-pane request)]
   [:div
    [:label.btn {:data-on-click (d*/sse-patch "/cmd/update-all")} "Update all tabs"]
    (shared-pane request)]])

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
     [:body.p-8
      {;:data-on-load (d*/sse-get "/connect")
       :data-on-signal-patch-filter "{include: /^tabid$/}";regex
       ;request: /cmd/init when tabid is set
       :data-on-signal-patch (d*/sse-patch "/cmd/init")
       ;generate a tab specific ID that will persist through a page refresh.
       :data-computed-tabid "(sessionStorage.getItem('tabId') ||
                               sessionStorage.setItem('tabId', crypto.randomUUID()) ||
                               sessionStorage.getItem('tabId'))"}
      [:div.mx-auto.max-w-7xl.sm:px-6.lg:px-8
       (view request)]]]]))

(defn on-connect [request]
  ;Called after the initial page render and we have a tabid signal.
  ;tabid is used as a key to persist state and a connection.
  ;Note: the ->sse-response closes the sse channel when the windows is hidden.
  (let [tabid (tabid request)]
    (->sse-response request
                    {hk-gen/on-open
                     (fn [sse-gen]
                       ;connections are associated with tabid
                       ;when the tab is eventualy closed tab specific state will have to
                       ;be disposed with something like com.github.ben-manes.caffeine/caffeine (not implemented).
                       (swap! !tab-states assoc-in [tabid :sse-gen] sse-gen)
                       ;render the view now that we have a tabid to retrieve the state.
                       (d*/patch-elements! sse-gen (-> request view html))
                       (d*/console-log! sse-gen (format "'connected; tabid: %s'", tabid))) ;cache sse connection
                     hk-gen/on-close
                     (fn on-close [_sse-gen status-code]
                       (swap! !tab-states update tabid dissoc :sse-gen)
                       (println "Connection closed status: " status-code)
                       (println (format "remove connection from pool; tabid: %s", tabid)))})))

(defn index [request]
  ;Render the initial page.
  ;Note there is no tabid yet so data will has to be rendered in on-connect.
  (-> (page request) html ruresp/response (ruresp/content-type "text/html")))

(defn on-update-this [request]
  (swap! !tab-states assoc-in [(tabid request) :unique-content] (new-content))
  (with-open-sse request d*/patch-elements! (-> request unique-pane html)))

(defn on-update-all [request]
  (swap! !shared-state assoc :shared-content (new-content))
  (broadcast request d*/patch-elements! (-> request shared-pane html))
  {:status 204})

(defn cmd-handler [request]
  (let [{:keys [cmd]} (get-in request [::r/match :path-params])
        request (assoc request :signals (some-> request d*/get-signals read-json))]
    (println (format "cmd: %s, from tabid: %s" cmd (tabid request)))
    ((case (keyword cmd)
       :init on-connect
       :update-this on-update-this
       :update-all on-update-all) request)))

(def routes
  [["/" {:get  #'index}]
   ["/cmd/:cmd" #'cmd-handler]])

(defonce !server (atom nil))

(defn stop! []
  (when-let [s @!server]
    (hks/server-stop! s)
    (reset! !server nil)))

(defn start! [opts]
  (stop!)
  (reset! !tab-states {})
  (reset! !shared-state {})
  (reset! !new-content-count 0)
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
  @!shared-state
  @!tab-states
  ;
  )

(defn -main [& _]
  (start! {:port 8085}))

