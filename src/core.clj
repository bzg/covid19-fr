;; Copyright (c) 2020 DINUM, Bastien Guerry <bastien.guerry@data.gouv.fr>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE

(ns core
  (:require [clj-http.lite.client :as http]
            [hickory.core :as h]
            [hickory.select :as hs]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [java-time :as t])
  (:gen-class))

(def data-url "https://www.santepubliquefrance.fr/maladies-et-traumatismes/maladies-et-infections-respiratoires/infection-a-coronavirus/articles/infection-au-nouveau-coronavirus-sars-cov-2-covid-19-france-et-monde")

;; https://www.data.gouv.fr/fr/admin/dataset/5e689ada634f4177317e4820/
(def datagouv-api "https://www.data.gouv.fr/api/1")
(def datagouv-api-token (System/getenv "DATAGOUV_API_TOKEN"))
(def csv-file-path (str (System/getProperty "user.home") "/covid19/covid19.csv"))
(def dataset "5e689ada634f4177317e4820")
(def resource "fa9b8fc8-35d5-4e24-90eb-9abe586b0fa5")

(defn upload-file-to-datagouv []
  (sh/sh "curl"
         "-H" "Accept:application/json"
         "-H" (str "X-Api-Key:" datagouv-api-token)
         "-F" (str "file=@" csv-file-path)
         "-X" "POST" (str datagouv-api "/datasets/" dataset
                          "/resources/" resource "/upload/")))

(defn get-covid19-raw-data []
  (if-let [data (try (http/get data-url {:cookie-policy :standard})
                     (catch Exception _ nil))]
    (let [out (-> data :body h/parse h/as-hickory
                  (as-> d (hs/select (hs/class "content__table-inner") d))
                  first :content first :content)]
      (:content (second out)))))

(defn get-covid19-data []
  (filter (fn [[_ c]] (string? c))
          (map (fn [[l r]]
                 [(first (:content l)) (first (:content r))])
               (map :content (get-covid19-raw-data)))))

(defn arrange-data [data]
  [(cons "Date" (map first data))
   (cons (t/format "dd/MM/yyyy" (t/zoned-date-time))
         (map second data))])

(defn -main []
  (let [hist (try (with-open [reader (io/reader csv-file-path)]
                    (doall
                     (csv/read-csv reader)))
                  (catch Exception _
                    (println "No initial covid19.csv file, creating new")))
        new  (arrange-data (get-covid19-data))]
    (if (= (drop 1 (last hist)) (drop 1 (last new)))
      (println "No update available")
      (do (with-open [writer (io/writer csv-file-path)]
            (csv/write-csv
             writer (concat (or (not-empty (take 1 hist)) (take 1 new))
                            (distinct (concat (rest hist) (rest new))))))
          (println "Wrote covid19.csv")
          (if (= 0 (:exit (upload-file-to-datagouv)))
            (println "covid19.csv uploaded to data.gouv.fr")
            (println "Error while trying to upload covid19.csv"))
          (System/exit 0)))))
