(ns user
  (:require
   [clj-reload.core :as reload]
   app))

(reload/init
 {:dirs      ["src" "src-dev"]
  :no-reload '#{user}})

(app/start! {:port 8085})

(comment
  (app/start! {:port 8085})
  (app/stop!))