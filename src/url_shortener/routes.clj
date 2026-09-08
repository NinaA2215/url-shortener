(ns url-shortener.routes
  (:require [reitit.ring :as ring]
            [url-shortener.handlers :as h]
            [ring.middleware.params :refer [wrap-params]]))

(def app
  (wrap-params
    (ring/ring-handler
      (ring/router
        [["/api/shorten" {:post h/shorten-handler}]
         ["/:code" {:get h/redirect-handler}]
         ["/" {:get h/html-handler}]
         ["/form/shorten" {:post h/html-shorten-handler}]
         ])
      (ring/create-default-handler)))
  )