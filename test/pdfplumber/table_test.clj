(ns pdfplumber.table-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.geometry :as g]))

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

(deftest text-strategy
  (pdf/with-pdf [d (fix/text-table-pdf)]
    (let [t (pdf/extract-table d {:page 1 :strategy :text})]
      (testing "rows and columns inferred from word alignment"
        (is (= [["Date" "Amount"]
                ["2026-01-01" "$10.00"]
                ["2026-02-01" "$20.00"]]
               (row-texts t))))
      (testing "result metadata"
        (is (= :text (:strategy t)))
        (is (= 1 (:page-number t)))
        (is (= 2 (get-in t [:debug :columns])))))))

(deftest extract-tables-returns-collection
  (pdf/with-pdf [d (fix/table-pdf)]
    (let [ts (pdf/extract-tables d {:page 1 :strategy :lines})]
      (is (vector? ts))
      (is (= 1 (count ts)))
      (is (= [["Date" "Amount"] ["2026-01-01" "$10.00"]]
             (row-texts (first ts)))))))

(deftest multiple-ruled-tables
  (pdf/with-pdf [d (fix/two-tables-pdf)]
    (let [tables (pdf/extract-tables d {:page 1})]
      (is (= 2 (count tables)))
      (is (= [["A" "B"] ["1" "2"]] (row-texts (first tables))))
      (is (= [["C" "D"] ["3" "4"]] (row-texts (second tables))))
      (is (every? #(= 4 (count (:cells %))) tables))
      (when (= 2 (count tables))
        (is (not (g/intersects? (:bbox (first tables))
                                (:bbox (second tables)))))))))

(deftest per-axis-text-strategies
  (pdf/with-pdf [d (fix/partially-ruled-table-pdf)]
    (let [tables (pdf/extract-tables d {:page 1
                                        :vertical-strategy :text
                                        :horizontal-strategy :lines})]
      (is (= 1 (count tables)))
      (is (= [["Date" "Amount"]
              ["2026-01-01" "$10.00"]
              ["2026-02-01" "$20.00"]]
             (row-texts (first tables)))))))

(deftest explicit-lines-strategies
  (pdf/with-pdf [d (fix/explicit-table-pdf)]
    (let [tables (pdf/extract-tables
                  d {:page 1
                     :vertical-strategy :explicit
                     :horizontal-strategy :explicit
                     :explicit-vertical-lines [70 170 260]
                     :explicit-horizontal-lines [80 110 140]})]
      (is (= 1 (count tables)))
      (is (= [["Left" "Right"] ["x" "y"]]
             (row-texts (first tables)))))))

(deftest singular-table-returns-first-detected-table
  (pdf/with-pdf [d (fix/two-tables-pdf)]
    (is (= [["A" "B"] ["1" "2"]]
           (row-texts (pdf/extract-table d {:page 1}))))))

(deftest no-table-detected
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (is (= [] (pdf/extract-tables d {:page 1})))
    (is (nil? (pdf/extract-table d {:page 1})))))
