(ns url-shortener.handlers
  (:require [clojure.string :as str]
            [url-shortener.db :as db]
            [cheshire.core :as json])
  (:import (com.fasterxml.jackson.core JsonParseException)
           (java.net MalformedURLException URL)))


(def base-url
  (or (System/getenv "BASE_URL")
                     "http://localhost:3000/"))
(def letters "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
(def short-code-length 6)
(def max-code-generation-attempts 5)
(def max-request-body-size 10000)
(def max-url-length 2048)

(defn html-handler [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "<form method=\"post\" action=\"/form/shorten\"><input type=\"text\" name=\"url\"><button type=\"submit\">Shorten</button></form>"}
  )

(defn random-shortener []
    (apply str (repeatedly short-code-length #(rand-nth letters))))

(defn generate-unique-code! [ds url]
  (loop [code (random-shortener)
         attempts 1]
    (if (> attempts max-code-generation-attempts)
      (throw (RuntimeException. "Unable to generate unique short code."))
      (let [result(db/insert-link! ds code url)]
        (if (= result :collision)
          (recur (random-shortener)(inc attempts))
          code)))))

(defn shorten-url! [ds url]
  (let [code (generate-unique-code! ds url)]
    {:code code
     :short-url (str base-url code)}))

;; Checking if passed url is blank and if it has valid protocol
(defn valid-url? [^String url]
  (try
    (let [address (URL. url)
          host (.getHost address)
          protocol (.getProtocol address)]
      (and
        (not (str/blank? host))
        (contains? #{"http" "https"} protocol)
        (<= (count url) max-url-length)))
    (catch MalformedURLException _
      false)))

(defn read-request-body [request]
  (let [body (:body request)
        buffer (byte-array 4096)
        output (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [read-bytes (.read body buffer)]
        (cond
          (= read-bytes -1)
          (.toString output "UTF-8")
          (> (+ (.size output) read-bytes) max-request-body-size)
          (throw (IllegalArgumentException. "Request body too large."))
          :else
          (do
            (.write output buffer 0 read-bytes)
            (recur)))))))

(defn shorten-handler [ds request]
  (let [body (try
               (json/parse-string (read-request-body request) true)
               (catch IllegalArgumentException _
                 ::body-too-large)
               (catch JsonParseException _
                ::invalid-json))]
    (cond
      (= body ::body-too-large)
      {:status 413                                          ;;Content Too Large
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Request body too large."})}
      (= body ::invalid-json)
      {:status 400                                          ;;Bad Request
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Invalid JSON."})}
      :else
    (let [url (:url body)]
      (if (or (str/blank? url) (not (valid-url? url)))
        {:status 400                                        ;;Bad Request
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error "No Valid URL found."})}
      (let [result (shorten-url! ds url)]
        {:status 201                                        ;;Created
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string result)}))))))

(defn redirect-handler [ds request]
  (let [code (get-in request [:path-params :code])
        link (db/find-by-code ds code)]
    (if link
      {:status 302                                          ;;Found
       :headers {"Location" (:original_url link)}}
      {:status 404                                          ;;Not Found
       :headers {"Content-Type" "text/plain"}
       :body "Short link not found"})))

(defn html-shorten-handler [ds request]
  (let [body (:form-params request)
        url (get body "url")]
    (if (or (str/blank? url) (not(valid-url? url)))
      {:status 400                                          ;;Bad Request
       :headers {"Content-Type" "text/html"}
       :body "<p>Invalid URL</p>"}
      (let [result (shorten-url! ds url)]
        {:status 200                                        ;;OK
         :headers {"Content-Type" "text/html"}
         :body (str "<a href=\"" (:short-url result) "\">" (:short-url result) "</a>")}))))