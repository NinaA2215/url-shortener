(ns url-shortener.db
  ;; Loading JDBC namespaces
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; Making a map, tells JDBC how to connect to database
(def db-configuration {:dbtype "sqlite" :dbname "shortener.db"})

;; Reads configuration and produces database object
(def ds (jdbc/get-datasource db-configuration))

(defn create-table! []
  (jdbc/execute! ds
                 ["CREATE TABLE IF NOT EXISTS links (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        code TEXT UNIQUE NOT NULL,
        original_url TEXT NOT NULL,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP
      )"]))

(defn insert-link! [code url]
  (jdbc/execute! ds
                 ["INSERT INTO links (code, original_url) VALUES (?, ?)" code url]))

(defn find-by-code [code]
  (jdbc/execute-one! ds
                     ["SELECT * FROM links WHERE code = ?" code]
                     {:builder-fn rs/as-unqualified-maps}))




