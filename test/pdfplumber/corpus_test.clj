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
            [pdfplumber.core :as pdf]))

(def ^:private golden-file (io/file "corpus/golden.json"))
(def ^:private corpus-dir "corpus/pdfplumber")
(def ^:private table-count-match-threshold 0.542)
(def ^:private table-shape-match-threshold 0.134)
(def ^:private table-cell-equality-threshold 0.833)

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

(defn- normalized-cell [cell]
  (str/trim (or cell "")))

(defn- probe [name]
  "Extract with pdfplumber-clj. Return {:pages :text :words :tables} or {:handled msg}
   for a graceful :pdfplumber/error, or {:crash class} for anything uncaught."
  (try
    (pdf/with-pdf [d (io/file corpus-dir name)]
      (let [pages (pdf/pages d)]
        {:pages (count pages)
         :text (str/join "\n" (map #(pdf/text d {:page (:page-number %)}) pages))
         :words (count (pdf/words d))
         :tables (vec (mapcat #(map table-rows
                                    (pdf/extract-tables d {:page (:page-number %)}))
                              pages))}))
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
          sims (map #(jaccard (:text %) (get-in % [:golden :text])) ok)
          word-ratios (for [r ok :let [gw (get-in r [:golden :words])] :when (pos? gw)]
                        (/ (double (:words r)) gw))
          missing-tables (filter #(not (contains? (:golden %) :tables)) ok)
          table-rows (remove #(not (contains? (:golden %) :tables)) ok)
          table-count-matches (filter #(= (count (:tables %))
                                          (count (get-in % [:golden :tables])))
                                      table-rows)
          table-pairs (mapcat (fn [r]
                                (map (fn [[clj-table python-table]]
                                       {:name (:name r)
                                        :clj clj-table
                                        :python python-table})
                                     (map vector (:tables r) (get-in r [:golden :tables]))))
                              table-rows)
          shape-pairs (filter #(= (table-shape (:clj %)) (table-shape (:python %)))
                              table-pairs)
          file-cell-equality (for [r table-rows
                                   :let [pairs (filter #(and (= (:name %) (:name r))
                                                            (= (table-shape (:clj %))
                                                               (table-shape (:python %))))
                                                       table-pairs)
                                         cells (mapcat (fn [{:keys [clj python]}]
                                                         (map vector (mapcat identity clj)
                                                              (mapcat identity python)))
                                                       pairs)]
                                   :when (seq cells)]
                               {:name (:name r)
                                :equality (/ (double (count (filter (fn [[a b]]
                                                                       (= (normalized-cell a)
                                                                          (normalized-cell b)))
                                                                     cells)))
                                             (count cells))})
          cell-equalities (map :equality file-cell-equality)
          worst-files (take 5 (sort-by (juxt :equality :name) file-cell-equality))]
      (println (format "\n[corpus] %d PDFs | %d crashes | %d compared | text-similarity median=%.3f min=%.3f | word-ratio median=%.3f"
                       (count rows) (count crashes) (count ok)
                       (or (median sims) 0.0) (or (when (seq sims) (apply min sims)) 0.0)
                       (or (median word-ratios) 0.0)))
      (when (seq crashes)
        (println "  crashes:" (mapv (juxt :name :crash) crashes)))
      (when (seq page-mismatch)
        (println "  page mismatch:" (mapv (juxt :name :pages #(get-in % [:golden :pages])) page-mismatch)))
      (println (format "[corpus tables] count-match=%.3f (%d/%d) | shape-match=%.3f (%d/%d) | cell-equality median=%.3f"
                       (if (seq table-rows) (/ (double (count table-count-matches)) (count table-rows)) 0.0)
                       (count table-count-matches) (count table-rows)
                       (if (seq table-pairs) (/ (double (count shape-pairs)) (count table-pairs)) 0.0)
                       (count shape-pairs) (count table-pairs)
                       (or (median cell-equalities) 0.0)))
      (when (seq worst-files)
        (println "  worst cell equality:" (mapv (juxt :name :equality) worst-files)))
      (when (< (count table-count-matches) (count table-rows))
        (println "  table count mismatch:"
                 (mapv (juxt :name #(count (:tables %))
                             #(count (get-in % [:golden :tables])))
                       (remove (set table-count-matches) table-rows))))
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
            (str "golden lacks :tables for " (mapv :name missing-tables))))
      (testing "aggregate table count match rate is stable"
        (is (>= (if (seq table-rows)
                  (/ (double (count table-count-matches)) (count table-rows))
                  0.0)
                table-count-match-threshold)))
      (testing "aggregate table shape match rate is stable"
        (is (>= (if (seq table-pairs)
                  (/ (double (count shape-pairs)) (count table-pairs))
                  0.0)
                table-shape-match-threshold)))
      (testing "median table cell equality is stable"
        (is (>= (or (median cell-equalities) 0.0)
                table-cell-equality-threshold))))))
