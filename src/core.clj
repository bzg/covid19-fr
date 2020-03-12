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
            [clojure.data.json :as datajson]
            [java-time :as t])
  (:gen-class))

(def data-url "https://www.santepubliquefrance.fr/maladies-et-traumatismes/maladies-et-infections-respiratoires/infection-a-coronavirus/articles/infection-au-nouveau-coronavirus-sars-cov-2-covid-19-france-et-monde")

;; https://www.data.gouv.fr/fr/admin/dataset/5e689ada634f4177317e4820/
(def datagouv-api "https://www.data.gouv.fr/api/1")
(def datagouv-api-token (System/getenv "DATAGOUV_API_TOKEN"))
(def csv-file-path (str (System/getProperty "user.home") "/covid19/covid19.csv"))
(def svg-file-path (str (System/getProperty "user.home") "/covid19/covid19.svg"))
(def dataset "5e689ada634f4177317e4820")
(def resource-csv "fa9b8fc8-35d5-4e24-90eb-9abe586b0fa5")
(def resource-svg "5ba293c5-30de-4d36-9d3c-cc2b2fd9faae")

(defn- rows->maps [csv]
  (let [headers (map keyword (first csv))
        rows    (rest csv)]
    (map #(zipmap headers %) rows)))

(defn csv-to-vega-data [csv]
  (flatten
   (map (fn [row]
          (let [date (:Date row)]
            (map (fn [[k v]]
                   {:region (name k) :cases v :date date})
                 row)))
        (rows->maps csv))))

(defn- temp-json-file
  "Convert `clj-vega-spec` to json and store it as tmp file."
  [clj-vega-spec]
  (let [tmp-file (java.io.File/createTempFile "vega." ".json")]
    (.deleteOnExit tmp-file)
    (with-open [file (io/writer tmp-file)]
      (datajson/write clj-vega-spec file))
    (.getAbsolutePath tmp-file)))

(defn vega-spec [csv]
  {:title    "Cas confirmés de contamination au COVID19 (Source: Santé Publique France)"
   :data     {:values (csv-to-vega-data csv)}
   :encoding {:x     {:field "date" :type "temporal"
                      :axis  {:title "Dates"}}
              :y     {:field "cases" :type "quantitative"
                      :axis  {:title "Cas confirmés"}}
              :color {:field "region"
                      :type  "nominal"}}
   :width    1200
   :height   600
   :mark     "line"})

(defn vega-chart! [csv]
  (sh/sh "vl2svg" (temp-json-file (vega-spec csv))
         svg-file-path))

(defn upload-to-datagouv []
  ;; Upload the csv
  (sh/sh "curl"
         "-H" "Accept:application/json"
         "-H" (str "X-Api-Key:" datagouv-api-token)
         "-F" (str "file=@" csv-file-path)
         "-X" "POST" (str datagouv-api "/datasets/" dataset
                          "/resources/" resource-csv "/upload/"))
  ;; Upload the svg
  (sh/sh "curl"
         "-H" "Accept:application/json"
         "-H" (str "X-Api-Key:" datagouv-api-token)
         "-F" (str "file=@" svg-file-path)
         "-X" "POST" (str datagouv-api "/datasets/" dataset
                          "/resources/" resource-svg "/upload/")))

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
   (cons (t/format "yyyy/MM/dd" (t/zoned-date-time))
         (map second data))])

(defn -main []
  (let [hist   (try (with-open [reader (io/reader csv-file-path)]
                      (doall
                       (csv/read-csv reader)))
                    (catch Exception _
                      (println "No initial covid19.csv file, creating new")))
        new    (arrange-data (get-covid19-data))
        merged (concat (or (not-empty (take 1 hist)) (take 1 new))
                       (distinct (concat (rest hist) (rest new))))]
    (if (= (drop 1 (last hist)) (drop 1 (last new)))
      (println "No update available")
      (do (with-open [writer (io/writer csv-file-path)]
            (csv/write-csv writer merged))
          (println "Wrote covid19.csv")
          (vega-chart! merged)
          (println "Wrote covid19.svg")
          (if (= 0 (:exit (upload-to-datagouv)))
            (println "covid19 resources uploaded to data.gouv.fr")
            (println "Error while trying to upload covid19.csv"))
          (System/exit 0)))))
