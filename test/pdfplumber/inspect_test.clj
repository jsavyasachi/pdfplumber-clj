(ns pdfplumber.inspect-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [pdfplumber.core :as pdf]))

(defn- normalized-cell [cell]
  (clojure.string/trim (clojure.string/replace (or cell "") #"\\s+" " ")))

(defn- page-cells [tables]
  (->> tables (mapcat identity) (mapcat identity) (map normalized-cell)
       (remove clojure.string/blank?) vec))

(deftest inspect
  (let [golden (json/read-str (slurp "corpus/golden.json") :key-fn keyword)]
    (doseq [name ["chelsea_pdta.pdf" "issue-1279-example.pdf"
                  "issue-1147-example.pdf" "issue-848.pdf"]]
      (let [g (get golden (keyword name))]
        (pdf/with-pdf [d (clojure.java.io/file "corpus/pdfplumber" name)]
          (let [pages (pdf/pages d)]
            (println "FILE" name)
            (doseq [[i [actual expected]]
                    (map-indexed vector
                                 (map vector
                                      (map #(mapv #(mapv :text %) (:rows %))
                                           (mapcat #(pdf/extract-tables d {:page (:page-number %)}) pages))
                                      (:tables g)))]
              (let [a (page-cells [actual]) e (page-cells [expected])]
                (when (or (seq a) (seq e))
                  (println "PAGE" (inc i) "py" (count e) "clj" (count a))
                  (when (not= e a)
                    (println " expected" (pr-str e))
                    (println " actual  " (pr-str a))))))))))
    (is true)))
