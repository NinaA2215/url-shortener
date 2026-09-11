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
  (jdbc/execute! test-ds ["DROP TABLE IF EXISTS links"])
  (db/create-table! test-ds))

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
  (is (false? (h/valid-url? ""))))

(deftest valid-url-too-long-test
  (let [long-url (str "http://example.com/"
                      (apply str (repeat 2050 "a")))]
    (is (= false (h/valid-url? long-url)))))

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

(deftest shorten-handler-invalid-url-test
  (let [request {:body (java.io.ByteArrayInputStream.
                         (.getBytes "{\"url\":\"invalid-url\"}"))}
        response (h/shorten-handler test-ds request)]
    (is (= 400 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))))

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
    (is (= "<p>Invalid URL</p>"
           (:body response)))))

(deftest shorten-url-test
  (let [result (h/shorten-url! test-ds "https://google.com")]
    (is (string? (:code result)))
    (is (= h/short-code-length (count (:code result))))
    (is (= (str h/base-url (:code result)) (:short-url result)))
    (is (= "https://google.com" (:original_url (db/find-by-code test-ds (:code result)))))))

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

(deftest shorten-handler-body-too-large-test
  (let [large-body (apply str (repeat (inc 10000) "a"))
        request {:body (java.io.ByteArrayInputStream. (.getBytes large-body))}
        response (h/shorten-handler test-ds request)
        body (json/parse-string (:body response) true)]
    (is (= 413 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"])))
    (is (= "Request body too large." (:error body)))))

