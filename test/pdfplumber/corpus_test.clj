(ns pdfplumber.corpus-test
  "Opt-in parity test against Python pdfplumber over a real-world corpus.

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

(defn- probe [name]
  "Extract with pdfplumber-clj; return {:pages :text :words} or {:handled msg}
   for a graceful :pdfplumber/error, or {:crash class} for anything uncaught."
  (try
    (pdf/with-pdf [d (io/file corpus-dir name)]
      {:pages (count (pdf/pages d))
       :text (str/join "\n" (map #(pdf/text d {:page (:page-number %)}) (pdf/pages d)))
       :words (count (pdf/words d))})
    (catch clojure.lang.ExceptionInfo e
      (if (:pdfplumber/error (ex-data e))
        {:handled (:pdfplumber/error (ex-data e))}
        {:crash (str "ExceptionInfo " (ex-message e))}))
    (catch Throwable t
      {:crash (.getName (class t))})))

(deftest ^:corpus python-pdfplumber-parity
  (if-not (.exists golden-file)
    (testing "corpus absent — skipped (run dev/fetch-corpus.sh + dev/gen_golden.py)"
      (is true))
    (let [golden (json/read-str (slurp golden-file) :key-fn keyword)
          rows (for [[fname g] golden
                     :let [name (clojure.core/name fname)
                           r (probe name)]]
                 (assoc r :name name :golden g))
          crashes (filter :crash rows)
          ;; Only compare where Python produced a real baseline (opened, >0 pages).
          ;; A 0-page / errored golden is a pdfminer parse failure, not a baseline.
          ok (filter (fn [r] (and (:pages r)
                                  (nil? (get-in r [:golden :error]))
                                  (pos? (get-in r [:golden :pages] 0)))) rows)
          page-mismatch (filter #(not= (:pages %) (get-in % [:golden :pages])) ok)
          sims (map #(jaccard (:text %) (get-in % [:golden :text])) ok)
          word-ratios (for [r ok :let [gw (get-in r [:golden :words])] :when (pos? gw)]
                        (/ (double (:words r)) gw))]
      (println (format "\n[corpus] %d PDFs | %d crashes | %d compared | text-similarity median=%.3f min=%.3f | word-ratio median=%.3f"
                       (count rows) (count crashes) (count ok)
                       (or (median sims) 0.0) (or (when (seq sims) (apply min sims)) 0.0)
                       (or (median word-ratios) 0.0)))
      (when (seq crashes)
        (println "  crashes:" (mapv (juxt :name :crash) crashes)))
      (when (seq page-mismatch)
        (println "  page mismatch:" (mapv (juxt :name :pages #(get-in % [:golden :pages])) page-mismatch)))
      (testing "no uncaught crashes on any real-world PDF"
        (is (empty? (mapv (juxt :name :crash) crashes))))
      (testing "page count matches Python pdfplumber"
        (is (empty? (mapv :name page-mismatch))))
      (testing "aggregate text similarity is high"
        (is (>= (or (median sims) 0.0) 0.80))))))
