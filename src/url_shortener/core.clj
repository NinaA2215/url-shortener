(ns url-shortener.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
             [url-shortener.routes :refer [app]]
             [url-shortener.db :as db])
  (:gen-class))

(defn -main [& args]
  (db/create-table!)
  (println "Server started!")
  (run-jetty app {:port 3000})
  )

