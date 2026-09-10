(ns url-shortener.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def db-configuration {:dbtype "sqlite" :dbname "shortener.db"})

(def ds (jdbc/get-datasource db-configuration))

(defn create-table! [ds]
  (jdbc/execute! ds
                 ["CREATE TABLE IF NOT EXISTS links (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        code TEXT UNIQUE NOT NULL,
        original_url TEXT NOT NULL,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP
      )"]))

(defn insert-link! [ds code url]
  (try
    (jdbc/execute! ds
                   ["INSERT INTO links (code, original_url) VALUES (?, ?)" code url])
    code
    (catch org.sqlite.SQLiteException e
      (if (= (.getResultCode e) org.sqlite.SQLiteErrorCode/SQLITE_CONSTRAINT_UNIQUE)
        :collision
        (throw e)))))

(defn find-by-code [ds code]
  (jdbc/execute-one! ds
                     ["SELECT code, original_url, created_at FROM links WHERE code = ?" code]
                     {:builder-fn rs/as-unqualified-maps}))