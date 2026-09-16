(ns url-shortener.handlers
  (:require [clojure.string :as str]
            [url-shortener.db :as db]
            [cheshire.core :as json]
            [hiccup2.core :as h])
  (:import (com.fasterxml.jackson.core JsonParseException)
           (java.net MalformedURLException URL)))

(def base-url
  (or (System/getenv "BASE_URL")
      "http://localhost:3000/"))

(def letters "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
(def short-code-length 6)
(def max-code-generation-attempts 5)
(def max-url-length 2048)
(def reserved-aliases #{"api" "form"})

(defn html-handler [_]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (str (h/html
                [:form {:method "post" :action "/form/shorten"}
                 [:input {:type "text" :name "url"}]
                 [:input {:type "text" :name "alias" :placeholder "Custom alias is optional"}]
                 [:button {:type "submit"} "Shorten"]]))})

(defn random-shortener []
  (apply str (repeatedly short-code-length #(rand-nth letters))))

(defn generate-unique-code! [ds url]
  (loop [code (random-shortener)
         attempts 1]
    (if (> attempts max-code-generation-attempts)
      (throw (RuntimeException.
               "Unable to generate unique short code."))
      (let [result (db/insert-link! ds code url)]
        (if (= result :collision)
          (recur (random-shortener)
                 (inc attempts))
          code)))))

(defn shorten-url! [ds url alias]
    (if (str/blank? alias)
      (let [code (generate-unique-code! ds url)]
        {:code code
         :short-url (str base-url code)})
      (let [result (db/insert-link! ds alias url)]
        (if (= :collision result)
          ::alias-taken
          {:code alias
           :short-url (str base-url alias)}))))

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

(defn valid-alias? [^String alias]
  (try
    (and (every? #(Character/isLetterOrDigit %) alias)
         (<= 3 (count alias) 20)
         (not (contains? reserved-aliases (str/lower-case alias))))
    (catch IllegalArgumentException _
      false)))

(defn shorten-handler [ds request]
  (let [body (try
               (json/parse-string (slurp (:body request)) true)
               (catch JsonParseException _
                 ::invalid-json))]
    (cond
      (= body ::invalid-json)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Invalid JSON."})}
      :else
      (let [url (:url body)
            alias (:alias body)]
        (if (or (str/blank? url)
                (not (valid-url? url)))
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:error "No Valid URL found."})}
          (if (and (not (str/blank? alias))
                   (not (valid-alias? alias)))
            {:status 400
             :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:error "Invalid alias."})}
            (try
              (let [result (shorten-url! ds url alias)]
                (if (= result ::alias-taken)
                  {:status 409
                   :headers {"Content-Type" "application/json"}
                   :body (json/generate-string {:error "Alias already taken."})}
                  {:status 201
                   :headers {"Content-Type" "application/json"}
                   :body (json/generate-string result)}))
              (catch RuntimeException _
                {:status 500
                 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string {:error "Unable to generate unique short code."})})
              (catch java.sql.SQLException _
                {:status 500
                 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string {:error "Database error occurred."})}))))))))

(defn redirect-handler [ds request]
  (let [code (get-in request [:path-params :code])
        link (db/find-by-code ds code)]
    (if link
        (do
          (db/record-click! ds code)
          {:status 302
           :headers {"Location" (:original_url link)}})
        {:status 404
        :headers {"Content-Type" "text/plain"}
        :body "Short link not found"})))

(defn html-shorten-handler [ds request]
  (let [body (:form-params request)
        url (get body "url")
        alias (get body "alias")]
    (if (or (str/blank? url)
            (not (valid-url? url)))
      {:status 400
       :headers {"Content-Type" "text/html"}
       :body "<p>Invalid URL.</p>"}
      (if (and (not (str/blank? alias))
                (not (valid-alias? alias)))
          {:status 400
           :headers {"Content-Type" "text/html"}
           :body "<p>Invalid alias.</p>"}
          (let [result (shorten-url! ds url alias)]
            (if (= ::alias-taken result)
              {:status 409
              :headers {"Content-Type" "text/html"}
              :body "<p>Alias already taken.</p>"}
              {:status 200
               :headers {"Content-Type" "text/html"}
               :body (str (h/html
                            [:ul
                             [:li [:a {:href (:short-url result)} (:short-url result)]]
                             [:li [:a {:href (str "/stats/" (:code result))} "Link stats "]]
                             ]))}))))))

(defn stats-handler [ds request]
  (let [code (get-in request [:path-params :code])
        stats (db/return-clicks ds code)]
    (if (= nil stats)
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Link statistic not found."})}
      (let [response {:original_url (:original_url stats)
                      :short-url (str base-url code)
                      :clicks_count (:clicks_count stats)
                      :last_accessed (:last_accessed stats)
                      :created_at (:created_at stats)}]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string response)}))))

(defn html-stats-handler [ds request]
  (let [code (get-in request [:path-params :code])
        stats (db/return-clicks ds code)]
    (if (= nil stats)
      {:status 404
       :headers {"Content-Type" "text/html"}
       :body "<p>Link statistic not found.</p>"}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (str (h/html [:ul
                           [:li (str "Original URL: " (:original_url stats))]
                           [:li (str "Short URL: " (str base-url code))]
                           [:li (str "Click count: " (:clicks_count stats))]
                           [:li (str "Last accessed: " (:last_accessed stats))]
                           [:li (str "Created at: " (:created_at stats))]]))})))
