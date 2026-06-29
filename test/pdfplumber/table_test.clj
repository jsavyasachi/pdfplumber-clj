(ns pdfplumber.table-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]))

(defn- row-texts [table]
  (mapv (fn [row] (mapv :text row)) (:rows table)))

(deftest lines-strategy
  (pdf/with-pdf [d (fix/table-pdf)]
    (let [t (pdf/extract-table d {:page 1 :strategy :lines})]
      (testing "rows and cells reconstructed from ruling lines"
        (is (= [["Date" "Amount"]
                ["2026-01-01" "$10.00"]]
               (row-texts t))))
      (testing "result metadata"
        (is (= :lines (:strategy t)))
        (is (= 1 (:page-number t)))
        (is (= 4 (count (:cells t))))
        (is (= 3 (get-in t [:debug :horizontal-lines])))
        (is (= 3 (get-in t [:debug :vertical-lines]))))
      (testing "cell maps carry a bbox"
        (is (every? (fn [row] (every? #(vector? (:bbox %)) row)) (:rows t)))))))

(deftest extract-tables-returns-collection
  (pdf/with-pdf [d (fix/table-pdf)]
    (let [ts (pdf/extract-tables d {:page 1 :strategy :lines})]
      (is (vector? ts))
      (is (= 1 (count ts)))
      (is (= [["Date" "Amount"] ["2026-01-01" "$10.00"]]
             (row-texts (first ts)))))))
