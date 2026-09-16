(ns url-shortener.handlers-test
  (:require [clojure.test :refer :all]
            [url-shortener.db :as db]
            [url-shortener.handlers :as h]
            [next.jdbc :as jdbc]
            [cheshire.core :as json]))

(def test-db-file
  (java.io.File/createTempFile "url-shortener-handler-test" ".db"))

(def test-ds
  (jdbc/get-datasource
    {:dbtype "sqlite"
     :dbname (.getAbsolutePath test-db-file)}))

(defn reset-db! []
  (jdbc/execute! test-ds ["DROP TABLE IF EXISTS clicks"])
  (jdbc/execute! test-ds ["DROP TABLE IF EXISTS links"])
  (db/create-table! test-ds)
  (db/create-click-table! test-ds))

(use-fixtures :each
              (fn [test]
                (reset-db!)
                (test)))

(use-fixtures :once
              (fn [tests]
                (tests)
                (.delete test-db-file)))

(deftest valid-url-test
  (is (true? (h/valid-url? "https://google.com")))
  (is (true? (h/valid-url? "http://example.com")))
  (is (false? (h/valid-url? "ftp://google.com")))
  (is (false? (h/valid-url? "https://")))
  (is (false? (h/valid-url? "fake url")))
  (is (false? (h/valid-url? "google.com ")))
  (is (false? (h/valid-url? "")))
  (let [long-url (str "http://example.com/"
                      (apply str (repeat 2050 "a")))]
    (is (= false (h/valid-url? long-url)))))

(deftest valid-alias-test
  (is (true? (h/valid-alias? "abc")))
  (is (false? (h/valid-alias? "ab")))
  (is (false? (h/valid-alias? "thisiswaytoolong12345")))
  (is (false? (h/valid-alias? "api")))
  (is (false? (h/valid-alias? "API")))
  (is (false? (h/valid-alias? "form")))
  (is (false? (h/valid-alias? "custom-alias"))))

(deftest random-shortener-test
  (let [code (h/random-shortener)]
    (is (string? code))
    (is (= h/short-code-length (count code)))
    (is (every? #(contains? (set h/letters) %) code))))

(deftest shorten-handler-test
  (let [request {:body (java.io.ByteArrayInputStream.
                         (.getBytes "{\"url\":\"https://google.com\"}"))}
        response (h/shorten-handler test-ds request)
        body (json/parse-string (:body response) true)]
    (is (= 201 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (string? (:body response)))
    (is (string? (:code body)))
    (is (= 6 (count (:code body))))
    (is (= (str h/base-url (:code body))
           (:short-url body)))
    (is (= "https://google.com"
           (:original_url
             (db/find-by-code test-ds (:code body)))))))

(deftest shorten-handler-alias-test
  (let [request {:body (java.io.ByteArrayInputStream.
                          (.getBytes "{\"url\":\"https://google.com\",\"alias\":\"google\"}"))}
        response (h/shorten-handler test-ds request)
        body (json/parse-string (:body response) true)]
    (is (= 201 (:status response)))
    (is (= "google" (:code body)))
    (is (= (str h/base-url "google") (:short-url body)))
    (is (= "https://google.com"
           (:original_url (db/find-by-code test-ds "google"))))))

(deftest shorten-handler-invalid-url-test
  (let [request {:body (java.io.ByteArrayInputStream.
                         (.getBytes "{\"url\":\"invalid-url\"}"))}
        response (h/shorten-handler test-ds request)]
    (is (= 400 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))))

(deftest shorten-handler-invalid-alias-test
  (let [request {:body (java.io.ByteArrayInputStream.
                         (.getBytes "{\"url\":\"https://google.com\",\"alias\":\"invalid-alias\"}"))}
        response (h/shorten-handler test-ds request)
        body (json/parse-string (:body response) true)]
    (is (= 400 (:status response)))
    (is (= "application/json"
        (get-in response [:headers "Content-Type"])))
    (is (= "Invalid alias." (:error body)))))

(deftest shorten-handler-alias-taken-test
  (db/insert-link! test-ds "google" "https://example.com")
  (let [request {:body (java.io.ByteArrayInputStream.
                         (.getBytes "{\"url\":\"https://google.com\",\"alias\":\"google\"}"))}
        response (h/shorten-handler test-ds request)
        body (json/parse-string (:body response) true)]
    (is (= 409 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (= "Alias already taken." (:error body)))))

(deftest shorten-handler-malformed-json-test
  (let [request {:body (java.io.ByteArrayInputStream.
                         (.getBytes "invalid json file"))}
        response (h/shorten-handler test-ds request)
        body (json/parse-string (:body response) true)]
    (is (= 400 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (= "Invalid JSON."
           (:error body)))))

(deftest shorten-handler-code-generation-failure-test
  (db/insert-link! test-ds "abc123" "https://exists.com")
  (with-redefs [h/random-shortener
                (fn [] "abc123")]
    (let [request {:body (java.io.ByteArrayInputStream.
                           (.getBytes "{\"url\":\"https://google.com\"}"))}
          response (h/shorten-handler test-ds request)
          body (json/parse-string (:body response) true)]
      (is (= 500 (:status response)))
      (is (= "application/json"
             (get-in response [:headers "Content-Type"])))
      (is (= "Unable to generate unique short code."
             (:error body))))))

(deftest shorten-handler-database-error-test
  (with-redefs [db/insert-link! (fn [_ _ _] (throw (java.sql.SQLException. "failure test")))]
    (let [request {:body (java.io.ByteArrayInputStream.
                           (.getBytes "{\"url\":\"https://google.com\"}"))}
          response (h/shorten-handler test-ds request)
          body (json/parse-string (:body response) true)]
      (is (= 500 (:status response)))
      (is (= "Database error occurred." (:error body))))))

(deftest redirect-handler-test
  (db/insert-link! test-ds "abc123" "https://google.com")
  (let [request {:path-params {:code "abc123"}}
        response (h/redirect-handler test-ds request)]
    (is (= 302 (:status response)))
    (is (= "https://google.com"
           (get-in response [:headers "Location"])))))

(deftest redirect-handler-missing-code-test
  (let [request {:path-params {:code "missing-code"}}
         response (h/redirect-handler test-ds request)]
    (is (= 404 (:status response)))
    (is (= "text/plain"
           (get-in response [:headers "Content-Type"])))
    (is (= "Short link not found"
           (:body response)))))

(deftest html-shorten-handler-test
  (let [request {:form-params {"url" "https://google.com"}}
        response (h/html-shorten-handler test-ds request)]
    (is (= 200 (:status response)))
    (is (= "text/html"
           (get-in response [:headers "Content-Type"])))
    (is (string? (:body response)))
    (is (re-find
          (re-pattern
            (str (java.util.regex.Pattern/quote h/base-url)
                 "[A-Za-z0-9]{"  h/short-code-length  "}"))
          (:body response)))))

(deftest html-shorten-handler-invalid-url-test
  (let [request {:form-params {"url" "invalid-url"}}
        response (h/html-shorten-handler test-ds request)]
    (is (= 400 (:status response)))
    (is (= "text/html"
           (get-in response [:headers "Content-Type"])))
    (is (= "<p>Invalid URL.</p>"
           (:body response)))))

(deftest generate-unique-code-collision-test
  (db/insert-link! test-ds "abc123" "https://exists.com")
  (let [codes (atom ["abc123" "xyz789"])]
    (with-redefs [h/random-shortener
                  (fn []
                    (let [code (first @codes)]
                      (swap! codes rest)
                      code))]
      (let [result (h/generate-unique-code! test-ds "https://google.com")]
        (is (= "xyz789" result))
        (is (= "https://google.com" (:original_url (db/find-by-code test-ds "xyz789"))))))))

(deftest redirect-handler-click-test
  (db/insert-link! test-ds "abc123" "https://google.com")
  (let [request {:path-params {:code "abc123"}}]
    (h/redirect-handler test-ds request)
    (let [stats (db/return-clicks test-ds "abc123")]
      (is (= 1 (:clicks_count stats))))))

(deftest stats-handler-test
  (db/insert-link! test-ds "abc123" "https://google.com")
  (let [request {:path-params {:code "abc123"}}
        response (h/stats-handler test-ds request)
        body (json/parse-string (:body response) true)]
    (is (= 200 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (= "https://google.com" (:original_url body)))
    (is (= (str h/base-url "abc123") (:short-url body)))
    (is (= 0 (:clicks_count body)))))

(deftest stats-handler-missing-code-test
  (let [request {:path-params {:code "missing-code"}}
        response (h/stats-handler test-ds request)
        body (json/parse-string (:body response) true)]
    (is (= 404 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (= "Link statistic not found." (:error body)))))

(deftest html-stats-handler-test
  (db/insert-link! test-ds "abc123" "https://google.com")
  (let [request {:path-params {:code "abc123"}}
        response (h/html-stats-handler test-ds request)]
  (is (= "text/html"
         (get-in response [:headers "Content-Type"])))
  (is (= 200 (:status response)))
  (is (string? (:body response)))
  (is (re-find #"Original URL: https://google.com" (:body response)))
  (is (re-find #"Click count: 0" (:body response)))))

(deftest html-stats-handler-missing-code-test
  (let [request {:path-params {:code "missing-code"}}
        response (h/html-stats-handler test-ds request)]
    (is (= 404 (:status response)))
    (is (= "text/html"
           (get-in response [:headers "Content-Type"])))
    (is (= "<p>Link statistic not found.</p>" (:body response)))))

(deftest html-shorten-handler-alias-test
  (let [request {:form-params {"url" "https://google.com" "alias" "alias1"}}
        response (h/html-shorten-handler test-ds request)]
    (is (= 200 (:status response)))
    (is (re-find #"alias1" (:body response)))
    (is (= "https://google.com"
           (:original_url (db/find-by-code test-ds "alias1"))))))