(ns pdfplumber.corpus-test
  "Optional parity test against Python pdfplumber with a real-world corpus.

   Reads `corpus/golden.json` (produced by `dev/gen_golden.py`) and the PDFs in
   `corpus/pdfplumber/`; both are gitignored. When the golden is absent the test
   is a no-op, so this runs clean in CI without the corpus. Generate locally:

       dev/fetch-corpus.sh
       .venv/bin/python dev/gen_golden.py"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [pdfplumber.core :as pdf])
  (:import [java.math BigDecimal RoundingMode]))

(def ^:private golden-file (io/file "corpus/golden.json"))
(def ^:private corpus-dir "corpus/pdfplumber")
(def ^:private table-cell-recall-threshold 0.90)
(def ^:private object-types [:rects :lines :curves :images :annots])
(def ^:private object-box-recall-thresholds
  {:rects 0.95
   :lines 0.95
   :curves 0.95
   :images 0.95
   :annots 0.95})
(def ^:private object-imperfect-page-ceilings
  {:rects 14
   :lines 2
   :curves 5
   :images 2
   :annots 0})

(def ^:private object-extractors
  {:rects pdf/rects
   :lines pdf/lines
   :curves pdf/curves
   :images pdf/images
   :annots pdf/annots})

(defn- tokens [s]
  (set (remove str/blank? (str/split (str/lower-case (or s "")) #"\s+"))))

(defn- jaccard [a b]
  (let [ta (tokens a) tb (tokens b)]
    (cond
      (and (empty? ta) (empty? tb)) 1.0
      (or (empty? ta) (empty? tb)) 0.0
      :else (/ (double (count (set/intersection ta tb)))
               (count (set/union ta tb))))))

(defn- median [xs]
  (when (seq xs)
    (let [v (vec (sort xs)) n (count v)]
      (if (odd? n) (nth v (quot n 2))
          (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2.0)))))

(defn- table-rows [table]
  (mapv (fn [row] (mapv :text row)) (:rows table)))

(defn- table-shape [table]
  [(count table) (reduce max 0 (map count table))])

(defn- shape-label [[rows columns]]
  (format "%dx%d" rows columns))

(defn- normalized-cell [cell]
  (str/trim (str/replace (or cell "") #"\s+" " ")))

(deftest normalizes-table-cell-whitespace
  (testing "cell whitespace does not affect content comparison"
    (is (= "NICS Firearm Background Checks November - 2015"
           (normalized-cell "  NICS Firearm Background Checks\nNovember\t- 2015  "))))
  (testing "whitespace-only cells are empty after normalization"
    (is (str/blank? (normalized-cell " \n\t ")))))

(defn- page-cells [tables]
  (->> tables
       (mapcat identity)
       (mapcat identity)
       (map normalized-cell)
       (remove str/blank?)
       vec))

(defn- same-value? [a b]
  (cond
    (and (number? a) (number? b)) (== a b)
    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b)) (every? true? (map same-value? a b)))
    :else (= a b)))

(defn- multiset-overlap [a b]
  (loop [expected (seq a)
         actual (vec b)
         matched 0]
    (if-let [cell (first expected)]
      (if-let [index (some (fn [index]
                             (when (same-value? cell (nth actual index))
                               index))
                           (range (count actual)))]
        (recur (next expected)
               (vec (concat (subvec actual 0 index)
                            (subvec actual (inc index))))
               (inc matched))
        (recur (next expected) actual matched))
      matched)))

(defn- multiset-recall [expected actual]
  (if (seq expected)
    (/ (double (multiset-overlap expected actual))
       (count expected))
    1.0))

(deftest compares-box-numbers-by-value
  (is (= 1.0
         (multiset-recall [[541.0 705.53 541.0 724.35]]
                          [[541 705.53 541 724.35]]))))

(defn- imperfect-page-count [page-recalls]
  (count (filter #(< (:recall %) 1.0) page-recalls)))

(deftest counts-imperfect-pages-per-object-type
  (is (= 2
         (imperfect-page-count [{:recall 1.0}
                                {:recall 0.0}
                                {:recall 0.5}]))))

(defn- rounded-coordinate [n]
  (.doubleValue (.setScale (BigDecimal. (double n)) 2 RoundingMode/HALF_EVEN)))

(defn- object-box [object]
  (mapv (comp rounded-coordinate object) [:x0 :top :x1 :bottom]))

(defn- object-record [objects]
  {:count (count objects)
   :boxes (mapv object-box objects)})

(defn- ellipsize
  "Shorten a cell to keep the printed report readable. Some corpus cells hold a
   whole page of text."
  [s]
  (let [s (str s)]
    (if (> (count s) 60) (str (subs s 0 60) "...") s)))

(defn- missing-cell-examples [expected actual]
  (loop [cells expected
         actual-counts (frequencies actual)
         missing []]
    (if-let [cell (first cells)]
      (if (pos? (get actual-counts cell 0))
        (recur (rest cells) (update actual-counts cell dec) missing)
        (recur (rest cells) actual-counts (conj missing cell)))
      (->> missing distinct (take 3) (mapv ellipsize)))))

(deftest finds-python-cell-examples-missing-from-clj
  (is (= ["one" "two" "three"]
         (missing-cell-examples ["one" "two" "two" "three"] ["two"]))))

(defn- page-table-golden? [golden]
  (and (vector? (:tables golden))
       (= (:pages golden) (count (:tables golden)))
       (every? (fn [page]
                 (and (vector? page)
                      (every? (fn [table]
                                (and (vector? table)
                                     (every? vector? table)))
                              page)))
               (:tables golden))))

(defn- page-object-golden? [golden object-type]
  (let [pages (get golden object-type)]
    (and (vector? pages)
         (= (:pages golden) (count pages))
         ;; A nil page means Python could not extract that object type there.
         ;; The key and the page count still prove the golden carries the field,
         ;; so a stale golden cannot pass by comparing nothing.
         (every? (fn [page]
                   (or (nil? page)
                    (and (map? page)
                        (integer? (:count page))
                        (not (neg? (:count page)))
                        (vector? (:boxes page))
                        (= (:count page) (count (:boxes page)))
                        (every? (fn [box]
                                  (and (vector? box)
                                       (= 4 (count box))
                                       (every? number? box)))
                                (:boxes page)))))
                 pages))))

(defn- probe [name]
  "Extract with pdfplumber-clj. Return {:pages :text :words :tables :objects} or {:handled msg}
   for a graceful :pdfplumber/error, or {:crash class} for anything uncaught."
  (try
    (pdf/with-pdf [d (io/file corpus-dir name)]
      (let [pages (pdf/pages d)]
        {:pages (count pages)
         :text (str/join "\n" (map #(pdf/text d {:page (:page-number %)}) pages))
         :words (count (pdf/words d))
         :tables (mapv #(mapv table-rows
                               (pdf/extract-tables d {:page (:page-number %)}))
                       pages)
         :objects (into {}
                        (map (fn [[object-type extract]]
                               [object-type
                                (mapv #(object-record
                                        (extract d {:page (:page-number %)}))
                                      pages)]))
                        object-extractors)}))
    (catch clojure.lang.ExceptionInfo e
      (if (:pdfplumber/error (ex-data e))
        {:handled (:pdfplumber/error (ex-data e))}
        {:crash (str "ExceptionInfo " (ex-message e))}))
    (catch Throwable t
      {:crash (.getName (class t))})))

(defn- required?
  "CI sets PDFPLUMBER_CORPUS_REQUIRED. A missing or empty golden then fails
   instead of passing as a skip. A scheduled parity job that goes green because
   the corpus never downloaded is worse than no job at all."
  []
  (some? (System/getenv "PDFPLUMBER_CORPUS_REQUIRED")))

(deftest ^:corpus python-pdfplumber-parity
  (if-not (.exists golden-file)
    (testing "corpus absent, skipped (run dev/fetch-corpus.sh + dev/gen_golden.py)"
      (is (not (required?))
          (str "PDFPLUMBER_CORPUS_REQUIRED is set but " golden-file " is missing. "
               "The corpus fetch or golden generation failed.")))
    (let [golden (json/read-str (slurp golden-file) :key-fn keyword)
          rows (for [[fname g] golden
                     :let [name (clojure.core/name fname)
                           r (probe name)]]
                 (assoc r :name name :golden g))
          crashes (filter :crash rows)
          ;; Compare only where Python produced a baseline with more than zero pages.
          ;; A zero-page or errored golden is a pdfminer parse failure, not a baseline.
          ok (filter (fn [r] (and (:pages r)
                                  (nil? (get-in r [:golden :error]))
                                  (pos? (get-in r [:golden :pages] 0)))) rows)
          page-mismatch (filter #(not= (:pages %) (get-in % [:golden :pages])) ok)
          text-similarities (map #(assoc % :similarity
                                          (jaccard (:text %) (get-in % [:golden :text]))) ok)
          sims (map :similarity text-similarities)
          word-ratios (for [r ok :let [gw (get-in r [:golden :words])] :when (pos? gw)]
                        (/ (double (:words r)) gw))
          missing-tables (remove #(page-table-golden? (:golden %)) ok)
          missing-objects (into {}
                                (map (fn [object-type]
                                       [object-type
                                        (remove #(page-object-golden? (:golden %) object-type)
                                                ok)]))
                                object-types)
          object-page-recalls
          (into {}
                (map (fn [object-type]
                       [object-type
                        (mapcat (fn [r]
                                  (map-indexed
                                   (fn [page-index [clj-page python-page]]
                                     {:name (:name r)
                                      :page (inc page-index)
                                      :recall (multiset-recall (:boxes python-page)
                                                                (:boxes clj-page))
                                      :count-match (= (:count clj-page)
                                                      (:count python-page))
                                      :python-count (:count python-page)
                                      :clj-count (:count clj-page)})
                                   (map vector (get-in r [:objects object-type])
                                        (get-in r [:golden object-type]))))
                                (remove (set (get missing-objects object-type)) ok))]))
                object-types)
          table-rows (remove (set missing-tables) ok)
          table-count-matches (filter #(= (reduce + 0 (map count (:tables %)))
                                          (reduce + 0 (map count (get-in % [:golden :tables]))))
                                      table-rows)
          table-pairs (mapcat (fn [r]
                                (mapcat (fn [[page-index [clj-page python-page]]]
                                          (map-indexed (fn [table-index [clj-table python-table]]
                                                         {:name (:name r)
                                                          :page (inc page-index)
                                                          :table (inc table-index)
                                                          :clj clj-table
                                                          :python python-table})
                                                       (map vector clj-page python-page)))
                                        (map-indexed vector
                                                     (map vector (:tables r)
                                                          (get-in r [:golden :tables])))))
                              table-rows)
          shape-pairs (filter #(= (table-shape (:clj %)) (table-shape (:python %)))
                              table-pairs)
          shape-mismatches (->> table-pairs
                                (remove #(= (table-shape (:clj %))
                                            (table-shape (:python %))))
                                (mapv (fn [{:keys [name page table clj python]}]
                                        {:file name
                                         :page page
                                         :table table
                                         :ours (shape-label (table-shape clj))
                                         :python (shape-label (table-shape python))})))
          page-cell-recalls (mapcat (fn [r]
                                      (keep-indexed
                                       (fn [page-index [clj-tables python-tables]]
                                         (let [clj-cells (page-cells clj-tables)
                                               python-cells (page-cells python-tables)]
                                           (when (or (seq clj-cells) (seq python-cells))
                                             {:name (:name r)
                                              :page (inc page-index)
                                              :recall (multiset-recall python-cells clj-cells)
                                              :python-cells (count python-cells)
                                              :clj-cells (count clj-cells)
                                              :missing-examples
                                              (missing-cell-examples python-cells clj-cells)
                                              :unmatched-clj-cells
                                              (- (count clj-cells)
                                                 (multiset-overlap clj-cells python-cells))})))
                                       (map vector (:tables r) (get-in r [:golden :tables]))))
                                    table-rows)
          file-cell-recalls (for [[name pages] (group-by :name page-cell-recalls)]
                              {:name name :recall (apply min (map :recall pages))})
          worst-files (take 5 (sort-by (juxt :recall :name) file-cell-recalls))
          worst-text-files (take 5 (sort-by (juxt :similarity :name)
                                            (map #(select-keys % [:name :similarity])
                                                 text-similarities)))
          zero-recall-pages (filter #(zero? (:recall %)) page-cell-recalls)
          content-gap-candidates (->> page-cell-recalls
                                      (filter #(and (< (:recall %) table-cell-recall-threshold)
                                                    (>= (:python-cells %) 5)))
                                      (group-by :name)
                                      vals
                                      (map #(first (sort-by (juxt :recall :page) %)))
                                      (sort-by :name))]
      (println (format "\n[corpus] %d PDFs | %d crashes | %d compared | text-similarity median=%.3f min=%.3f | word-ratio median=%.3f"
                       (count rows) (count crashes) (count ok)
                       (or (median sims) 0.0) (or (when (seq sims) (apply min sims)) 0.0)
                       (or (median word-ratios) 0.0)))
      (when (seq crashes)
        (println "  crashes:" (mapv (juxt :name :crash) crashes)))
      (when (seq worst-text-files)
        (println "  worst text similarity:" (mapv (juxt :name :similarity) worst-text-files)))
      (let [zero-text-files (->> text-similarities
                                 (filter #(zero? (:similarity %)))
                                 (mapv :name))]
        (println "  zero text similarity:" (count zero-text-files) zero-text-files))
      (when (seq page-mismatch)
        (println "  page mismatch:" (mapv (juxt :name :pages #(get-in % [:golden :pages])) page-mismatch)))
      (println (format "[corpus tables] count-match=%.3f (%d/%d) | shape-match=%.3f (%d/%d) | cell-recall median=%.3f | zero-recall-pages=%d"
                       (if (seq table-rows) (/ (double (count table-count-matches)) (count table-rows)) 0.0)
                       (count table-count-matches) (count table-rows)
                       (if (seq table-pairs) (/ (double (count shape-pairs)) (count table-pairs)) 0.0)
                       (count shape-pairs) (count table-pairs)
                       (or (median (map :recall page-cell-recalls)) 0.0)
                       (count zero-recall-pages)))
      (when (seq shape-mismatches)
        (println "  shape mismatches:" shape-mismatches))
      (when (seq worst-files)
        (println "  worst cell recall:" (mapv (juxt :name :recall) worst-files)))
      (when (seq content-gap-candidates)
        (println "  low page recall:")
        (doseq [c content-gap-candidates]
          (println (format "    %-46s p%-3d recall=%.3f py=%-4d clj=%-4d %s"
                           (:name c) (:page c) (:recall c)
                           (:python-cells c) (:clj-cells c)
                           (pr-str (:missing-examples c))))))
      (let [selected-files #{"issue-1279-example.pdf" "chelsea_pdta.pdf"}
            selected-recalls (for [[name pages] (group-by :name page-cell-recalls)
                                   :when (contains? selected-files name)]
                               {:file name :recall (apply min (map :recall pages))})]
        (println "  selected cell recall:" (sort-by :file selected-recalls)))
      (when (< (count table-count-matches) (count table-rows))
        (println "  table count mismatch:"
                 (mapv (juxt :name #(reduce + 0 (map count (:tables %)))
                             #(reduce + 0 (map count (get-in % [:golden :tables]))))
                       (remove (set table-count-matches) table-rows))))
      (doseq [object-type object-types]
        (let [page-recalls (get object-page-recalls object-type)
              count-matches (filter :count-match page-recalls)
              imperfect-pages (imperfect-page-count page-recalls)
              file-recalls (for [[name pages] (group-by :name page-recalls)]
                             {:name name :recall (apply min (map :recall pages))})
              worst-files (take 5 (sort-by (juxt :recall :name) file-recalls))]
          (println (format "[corpus %s] count-match=%.3f (%d/%d) | box-recall median=%.3f | imperfect-pages=%d"
                           (name object-type)
                           (if (seq page-recalls)
                             (/ (double (count count-matches)) (count page-recalls))
                           0.0)
                           (count count-matches) (count page-recalls)
                           (or (median (map :recall page-recalls)) 0.0)
                           imperfect-pages))
          (when (seq worst-files)
            (println "  worst box recall:" (mapv (juxt :name :recall) worst-files)))
          (let [count-mismatches (->> page-recalls
                                      (remove :count-match)
                                      (mapv (fn [{:keys [name page clj-count python-count]}]
                                              {:file name
                                               :page page
                                               :ours clj-count
                                               :python python-count})))]
            (when (seq count-mismatches)
              (println "  count mismatch:" count-mismatches)))))
      (testing "the golden actually holds a corpus"
        ;; This guards the other direction. A golden that compares nothing could
        ;; otherwise satisfy each assertion below vacuously.
        (is (or (not (required?)) (>= (count ok) 50))
            (str "only " (count ok) " comparable PDFs in " golden-file)))
      (testing "no uncaught crashes on any real-world PDF"
        (is (empty? (mapv (juxt :name :crash) crashes))))
      (testing "page count matches Python pdfplumber"
        (is (empty? (mapv :name page-mismatch))))
      ;; The test asserts similarity on the MEDIAN. Some corpus PDFs score near
      ;; zero because Python pdfplumber is wrong. A minimum would make its bugs
      ;; part of this contract:
      ;;   annotations-rotated-180.pdf  Python emits "elif FDP ymmuD" for
      ;;                                "Dummy PDF file"; it does not handle
      ;;                                180-degree page rotation.
      ;;   issue-842-example.pdf        Python repeats every CJK glyph four
      ;;                                times.
      ;;   extra-attrs-example.pdf      Line-break placement only.
      ;; Investigate a lower median, not one low score.
      (testing "aggregate text similarity is high"
        (is (>= (or (median sims) 0.0) 0.80)))
      (testing "the golden records tables for every comparable PDF"
        (is (empty? (mapv :name missing-tables))
            (str "golden lacks per-page :tables for " (mapv :name missing-tables))))
      (testing "the golden records page objects for every comparable PDF"
        (doseq [object-type object-types]
          (let [missing (get missing-objects object-type)]
            (is (empty? (mapv :name missing))
                (str "golden lacks per-page " object-type " for "
                     (mapv :name missing))))))
      ;; The test asserts recall on the MEDIAN. Some corpus PDFs have divergent
      ;; object extraction in Python pdfplumber. A minimum would make its bugs
      ;; part of this contract. Investigate a lower median, not one low score.
      (testing "aggregate page object box recall is high"
        (doseq [object-type object-types]
          (is (>= (or (median (map :recall (get object-page-recalls object-type))) 0.0)
                  (get object-box-recall-thresholds object-type))
              (str object-type " box recall fell below "
                   (get object-box-recall-thresholds object-type)))))
      (testing "per-page object box recall stays within imperfect-page ceilings"
        (doseq [object-type object-types]
          (let [imperfect-pages (imperfect-page-count
                                 (get object-page-recalls object-type))
                ceiling (get object-imperfect-page-ceilings object-type)]
            (is (<= imperfect-pages ceiling)
                (str object-type " has " imperfect-pages
                     " imperfect pages, above ceiling " ceiling)))))
      ;; The test asserts recall on the MEDIAN. Some corpus PDFs have damaged
      ;; text extraction in Python pdfplumber. A minimum would make its bugs
      ;; part of this contract. Investigate a lower median, not one low score.
      (testing "aggregate table cell recall is high"
        (is (>= (or (median (map :recall page-cell-recalls)) 0.0)
                table-cell-recall-threshold))))))
