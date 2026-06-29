(ns pdfplumber.golden-test
  "Golden-output regression test: a deterministic fixture extracted and compared
   against committed EDN with a numeric coordinate tolerance. Pins extraction
   behavior against silent regressions (e.g. a PDFBox metric change)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]))

(def ^:private tolerance 0.75)

(defn- approx= [a b]
  (<= (Math/abs (- (double a) (double b))) tolerance))

(defn- word≈ [expected actual]
  (and (= (:text expected) (:text actual))
       (= (:page-number expected) (:page-number actual))
       (every? #(approx= (get expected %) (get actual %))
               [:x0 :top :x1 :bottom])))

(deftest words-golden
  (let [expected (edn/read-string (slurp (io/resource "golden/simple-words.edn")))]
    (pdf/with-pdf [d (fix/simple-text-pdf)]
      (let [actual (pdf/words d)]
        (is (= (count expected) (count actual)))
        (doseq [[e a] (map vector expected actual)]
          (is (word≈ e a) (str "golden mismatch:\n  expected " e "\n  actual   " a)))))))
