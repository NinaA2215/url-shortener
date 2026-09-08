(ns url-shortener.handlers
  (:require [clojure.string :as str]
            [url-shortener.db :as db]
            [cheshire.core :as json])
  (:import (com.fasterxml.jackson.core JsonParseException)
           (java.net MalformedURLException URL)
           (org.sqlite SQLiteErrorCode SQLiteException)))


(def base-url "http://localhost:3000/")

(defn html-handler [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "<form method=\"post\" action=\"/form/shorten\"><input type=\"text\" name=\"url\"><button type=\"submit\">Shorten</button></form>"}
  )

(defn random-shortener []
  (let [letters "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"]
    (apply str (repeatedly 6 #(rand-nth letters)))))

(defn generate-unique-code! [url]
  (loop [code (random-shortener)
         attempts 1]
    (if (> attempts 5)
      (throw (RuntimeException. "An error has occurred."))
      (let [result (try (db/insert-link! code url)
                       code
                       (catch SQLiteException e
                         (if (= (.getResultCode e) SQLiteErrorCode/SQLITE_CONSTRAINT_UNIQUE)
                           :collision
                           (throw e))))]
        (if (= result :collision)
          (recur (random-shortener)(inc attempts))
          code)))))

;; Checking if passed url is blank and if it has valid protocol
(defn valid-url? [^String url]
  (try
    (let [address (URL. url)]
      (if (and (not (str/blank? (.getHost address))) (not (str/blank? (.getProtocol address)))
               (str/starts-with? address "http"))
        true
        (throw (MalformedURLException. "Not a valid URL.")))
      )
    (catch MalformedURLException _
      false)))


(defn shorten-handler [request]
  (let [body (try
               (json/parse-string (slurp (:body request)) true)
               (catch JsonParseException je
                nil))
        url (:url body)]
    (if  (or (str/blank? url) (not (valid-url? url)))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "No Valid URL found."})}
    (let [code (generate-unique-code! url)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:code code
                                    :short-url (str base-url code)})}))))

(defn redirect-handler [request]
  (let [code (get-in request [:path-params :code])
        link (db/find-by-code code)]
    (if link
      {:status 302
       :headers {"Location" (:original_url link)}}
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Short link not found"})))

(defn html-shorten-handler [request]
  (let [body (:form-params request)
        url (get body "url")]
    (if (or (str/blank? url) (not(valid-url? url)))
      {:status 400
       :headers {"Content-Type" "text/html"}
       :body "<p>Invalid URL</p>"}
      (let [code (generate-unique-code! url)]
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (str "<a href=\"" base-url code "\">" base-url code "</a>")}))
      ))


