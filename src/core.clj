;; Copyright (c) 2020 DINUM, Bastien Guerry <bastien.guerry@data.gouv.fr>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE

(ns core
  (:require [clj-http.lite.client :as http]
            [hickory.core :as h]
            [hickory.select :as hs]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [java-time :as t])
  (:gen-class))

(def data-url "https://www.santepubliquefrance.fr/maladies-et-traumatismes/maladies-et-infections-respiratoires/infection-a-coronavirus/articles/infection-au-nouveau-coronavirus-sars-cov-2-covid-19-france-et-monde")

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
  (let [hist (try (with-open [reader (io/reader "covid19.csv")]
                    (doall
                     (csv/read-csv reader)))
                  (catch Exception _
                    (println "Creating covid19.csv file")))
        new  (arrange-data (get-covid19-data))]
    (if (= (drop 1 (last hist)) (drop 1 (last new)))
      (println "No update available")
      (do (with-open [writer (io/writer "covid19.csv")]
            (csv/write-csv
             writer (concat (or (not-empty (take 1 hist)) (take 1 new))
                            (distinct (concat (rest hist) (rest new))))))
          (println "Wrote covid19.csv")))))
