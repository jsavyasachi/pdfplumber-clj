(ns pdfplumber.table-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.geometry :as g]))

(defn- row-texts [table]
  (mapv (fn [row] (mapv :text row)) (:rows table)))

(defn- private-var [name]
  (deref (ns-resolve 'pdfplumber.table name)))

(deftest edge-min-length-prefilter-settings
  (let [normalize-options (private-var 'normalize-options)
        normalize-edges (private-var 'normalize-edges)
        raw-edges {:h [{:y 10.0 :x0 20.0 :x1 20.5}]
                   :v [{:x 30.0 :top 40.0 :bottom 40.5}]}
        base (assoc (normalize-options {}) :edge-min-length 0.0)]
    (testing "default uses one unit"
      (is (= 1.0 (:edge-min-length-prefilter (normalize-options {}))))
      (is (= {:h [] :v []}
             (normalize-edges raw-edges base))))
    (testing "numeric value is used"
      (let [opts (normalize-options (assoc base :edge-min-length-prefilter 0.25))]
        (is (= 0.25 (:edge-min-length-prefilter opts)))
        (is (= 1 (count (:h (normalize-edges raw-edges opts)))))
        (is (= 1 (count (:v (normalize-edges raw-edges opts)))))))
    (testing "boolean values remain compatible"
      (let [false-opts (assoc base :edge-min-length-prefilter false)
            true-opts (assoc base :edge-min-length-prefilter true)]
        (is (= 0.0 (:edge-min-length-prefilter (normalize-options false-opts))))
        (is (= 1.0 (:edge-min-length-prefilter (normalize-options true-opts))))
        (is (= 1 (count (:h (normalize-edges raw-edges
                                             (normalize-options false-opts))))))
        (is (= 0 (count (:h (normalize-edges raw-edges
                                            (normalize-options true-opts))))))))))

(deftest partial-cell-borders-share-a-component
  (let [cell-components (ns-resolve 'pdfplumber.table 'cell-components)
        cells [[0.0 0.0 1.0 2.0]
               [1.0 0.0 2.0 1.0]
               [1.0 1.0 2.0 2.0]]]
    (is (= 1 (count (cell-components cells 0.01))))))

(deftest cells-without-shared-corners-stay-separate
  (let [cell-components (ns-resolve 'pdfplumber.table 'cell-components)
        cells [[0.0 0.0 1.0 1.0]
               [1.5 0.0 2.5 1.0]]]
    (is (= 2 (count (cell-components cells 3.0))))))

(deftest cell-text-uses-characters-when-a-word-crosses-a-cell
  (let [cell-text (private-var 'cell-text)
        words [{:text "Basse" :x0 10.0 :top 1.0 :x1 20.0 :bottom 8.0}]
        chars (map-indexed (fn [i letter]
                             {:text (str letter)
                              :x0 (+ 10.0 (* 2.0 i))
                              :top 1.0
                              :x1 (+ 12.0 (* 2.0 i))
                              :bottom 8.0
                              :y0 12.0
                              :upright true})
                           "Basse")]
    (is (= "Bas" (cell-text words chars [0.0 0.0 16.0 20.0] {})))))

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

(deftest lines-strategy-uses-polyline-curves
  (pdf/with-pdf [d (fix/polyline-table-pdf)]
    (let [t (pdf/extract-table d {:page 1 :strategy :lines})]
      (is (= [["Date" "Amount"]
              ["2026-01-01" "$10.00"]]
             (row-texts t)))
      (is (= [] (pdf/extract-tables d {:page 1 :strategy :lines-strict}))))))

(deftest rotated-lines-strategy
  (pdf/with-pdf [d (fix/rotated-table-pdf)]
    (let [t (pdf/extract-table d {:page 1 :strategy :lines})]
      (is (= [["Date" "Amount" "Count"]
              ["2026-01-01" "$10.00" "2"]]
             (row-texts t))))))

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

(deftest side-by-side-ruled-tables
  (pdf/with-pdf [d (fix/side-by-side-tables-pdf)]
    (let [tables (pdf/extract-tables d {:page 1})]
      (is (= 2 (count tables)))
      (is (every? #(= [5 3]
                      [(count (:rows %))
                       (reduce max 0 (map count (:rows %)))])
                  tables))
      (is (= [["FooCol1" "FooCol2" "FooCol3"]
              ["Foo4" "Foo5" "Foo6"]
              ["Foo7" "Foo8" "Foo9"]
              ["Foo10" "Foo11" "Foo12"]
              ["" "" ""]]
             (row-texts (first tables))))
      (is (= [["BarCol1" "BarCol2" "BarCol3"]
              ["Bar4" "Bar5" "Bar6"]
              ["Bar7" "Bar8" "Bar9"]
              ["Bar10" "Bar11" "Bar12"]
              ["" "" ""]]
             (row-texts (second tables)))))))

(deftest side-by-side-ruled-tables-with-offset-mediabox
  (pdf/with-pdf [d (fix/side-by-side-tables-offset-pdf)]
    (let [cells (->> (pdf/extract-tables d {:page 1})
                     (mapcat :rows)
                     (mapcat identity)
                     (map :text)
                     (remove clojure.string/blank?))]
      (is (= 24 (count cells)))
      (is (= #{"FooCol1" "FooCol2" "FooCol3" "Foo4" "Foo5" "Foo6"
               "Foo7" "Foo8" "Foo9" "Foo10" "Foo11" "Foo12"
               "BarCol1" "BarCol2" "BarCol3" "Bar4" "Bar5" "Bar6"
               "Bar7" "Bar8" "Bar9" "Bar10" "Bar11" "Bar12"}
             (set cells))))))

(deftest ruled-table-with-offset-cropbox
  (pdf/with-pdf [d (fix/cropbox-offset-table-pdf)]
    (let [t (pdf/extract-table d {:page 1 :strategy :lines})]
      (is (= [["Date" "Amount"]
              ["2026-01-01" "$10.00"]]
             (row-texts t)))
      (is (= 4 (count (:cells t)))))))

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

(deftest single-cell-candidates-are-not-tables
  (pdf/with-pdf [d (fix/ruled-pdf)]
    (is (= [] (pdf/extract-tables d {:page 1})))
    (is (nil? (pdf/extract-table d {:page 1})))))

(deftest snaps-near-tolerance-cell-boundaries
  (pdf/with-pdf [d (fix/near-tolerance-cells-pdf)]
    (is (= 1 (count (pdf/extract-tables d {:page 1}))))
    (let [clusters (private-var 'clusters)
          snap-values (private-var 'snap-values)]
      (is (= 1 (count (clusters [397.1999816894531 400.20001220703125] 3.0))))
      (is (= 1 (count (clusters [565.19995 568.20004] 3.0))))
      (is (= [3.0 3.0 3.0]
             (snap-values [0.0 3.0 6.0] 3.0)))
      (let [values (vec (concat (range 0.0 101.0) [102.0]))]
        (is (= 50.0 (last (butlast (snap-values values 1.0)))))))))

(deftest complete-settings-and-finder
  (pdf/with-pdf [d (fix/partially-ruled-table-pdf)]
    (testing "explicit lines are additive to a non-explicit strategy"
      (let [t (pdf/extract-table d {:page 1
                                    :vertical-strategy :lines
                                    :horizontal-strategy :lines
                                    :explicit-vertical-lines [70 300 400]
                                    :snap-x-tolerance 1.0
                                    :snap-y-tolerance 2.0
                                    :join-x-tolerance 1.0
                                    :join-y-tolerance 2.0
                                    :intersection-x-tolerance 2.0
                                    :intersection-y-tolerance 2.0
                                    :edge-min-length-prefilter true
                                    :text-keep-blank-chars false})]
        (is (= 1 (:page-number t)))
        (is (= 6 (count (:cells t)))))))
  (pdf/with-pdf [d (fix/table-pdf)]
    (let [find-tables-var (ns-resolve 'pdfplumber.core 'find-tables)
          find-table-var (ns-resolve 'pdfplumber.core 'find-table)]
      (is (every? some? [find-tables-var find-table-var]))
      (when (and find-tables-var find-table-var)
        (let [tables (find-tables-var d {:page 1})
              table (find-table-var d {:page 1})]
          (is (= 1 (count tables)))
          (is (= (:bbox table) (:bbox (first tables))))
          (is (= 2 (count (:columns table))))
          (is (= 4 (count (:cells table))))
          (is (fn? (:extract table)))
          (is (= [["Date" "Amount"] ["2026-01-01" "$10.00"]]
                 ((:extract table))))))))
  (pdf/with-pdf [d (fix/right-aligned-text-table-pdf)]
    (testing "text strategy considers right-edge alignments"
      (is (= 1 (count (pdf/extract-tables d {:page 1 :strategy :text})))))))
