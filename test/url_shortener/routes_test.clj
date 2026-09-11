(ns url-shortener.routes-test
  (:require [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [url-shortener.db :as db]
            [url-shortener.routes :refer [app]]))

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

(deftest home-route-test
  (let [request {:request-method :get
                 :uri "/"}
        response (app request)]
    (is (= 200 (:status response)))
    (is (= "text/html"
           (get-in response [:headers "Content-Type"])))))

(deftest shorten-api-route-test
  (with-redefs [db/ds test-ds]
    (let [request {:request-method :post
                   :uri "/api/shorten"
                   :headers {"content-type" "application/json"}
                   :body (java.io.ByteArrayInputStream.
                           (.getBytes "{\"url\":\"https://google.com\"}"))}
        response (app request)]
    (is (= 201 (:status response)))
    (is (= "application/json"
           (get-in response [:headers "Content-Type"]))))))

(deftest redirect--route-test
  (db/insert-link! test-ds "abc123" "https://exists.com")
  (with-redefs [db/ds test-ds]
    (let [request {:request-method :get
                   :uri "/abc123"}
          response (app request)]
      (is (= 302 (:status response)))
      (is (= "https://exists.com"
             (get-in response [:headers "Location"]))))))

(deftest html-shorten-route-test
  (with-redefs [db/ds test-ds]
    (let [request {:request-method :post
                   :uri "/form/shorten"
                   :headers {"content-type" "application/x-www-form-urlencoded"}
                   :body (java.io.ByteArrayInputStream.
                           (.getBytes "url=https://google.com"))}
          response (app request)]
      (is (= 200 (:status response)))
      (is (= "text/html"
             (get-in response [:headers "Content-Type"]))))))


