(ns url-shortener.routes
  (:require [reitit.ring :as ring]
            [url-shortener.handlers :as h]
            [url-shortener.db :as db]
            [ring.middleware.params :refer [wrap-params]]))

(def app
    (ring/ring-handler
      (ring/router
        [["/api/shorten"
          {:post (fn [request]
                   (h/shorten-handler db/ds request))}]

         ["/:code"
          {:get (fn [request]
                  (h/redirect-handler db/ds request))}]

         ["/"
          {:get h/html-handler}]

         ["/form/shorten"
          {:post (fn [request]
                   (h/html-shorten-handler db/ds request))
           :middleware [wrap-params]}]

         ["/api/stats/:code"
          {:get (fn [request]
                  (h/stats-handler db/ds request))}]

         ["/stats/:code"
          {:get (fn [request]
                  (h/html-stats-handler db/ds request))}]
         ])
      (ring/create-default-handler)))
