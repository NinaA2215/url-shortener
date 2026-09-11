(ns url-shortener.db-test
  (:require [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [url-shortener.db :as db]))

(def test-db-file
  (java.io.File/createTempFile "url-shortener-test" ".db"))

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

(deftest create-table-test
  (is (some? (jdbc/execute-one! test-ds
                                ["SELECT name FROM sqlite_master
                                WHERE type = 'table'
                                AND name = 'links'"]))))

(deftest insert-and-find-link-test
  (db/insert-link! test-ds "abc123" "https://google.com")
  (let [link (db/find-by-code test-ds "abc123")]
    (is (= "abc123" (:code link)))
    (is (= "https://google.com" (:original_url link)))))

(deftest missing-link-test
  (is (nil? (db/find-by-code test-ds "code-doesnt-exist"))))

(deftest duplicate-code-test
 (db/insert-link! test-ds "abc123" "https://google.com")
 (is (= :collision
        (db/insert-link! test-ds "abc123" "https://google.com"))))