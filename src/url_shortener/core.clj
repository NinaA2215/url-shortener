(ns url-shortener.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
             [url-shortener.routes :refer [app]]
             [url-shortener.db :as db])
  (:gen-class))

(def port
  (Integer/parseInt
    (or (System/getenv "PORT")
        "3000")))

(defn -main [& args]
  (db/create-table! db/ds)
  (db/create-click-table! db/ds)
  (println "Server started!")
  (let [server (run-jetty app {:port port
                               :join? false})]
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. (fn []
                 (println "Shutting down the server.")
                 (.stop server))))))

