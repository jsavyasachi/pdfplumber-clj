(ns pdfplumber.text-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]))

(deftest chars-extraction
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (let [cs (pdf/chars d)]
      (testing "captures every glyph (ignoring spacing)"
        (is (= "HelloPDF" (str/replace (apply str (map :text cs)) #"\s" ""))))
      (testing "char maps carry position, font, and page"
        (let [h (first cs)]
          (is (every? #(contains? h %) [:text :x0 :top :x1 :bottom :font-name :font-size :page-number]))
          (is (= 1 (:page-number h)))
          (is (< (:top h) (:bottom h)))         ; top-left origin
          (is (< 11.0 (:font-size h) 13.0))     ; drawn at 12pt
          (is (re-find #"(?i)helvetica" (:font-name h))))))))

(deftest words-grouping
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (testing "splits on the space into two words"
      (is (= ["Hello" "PDF"] (mapv :text (pdf/words d)))))
    (testing "word bbox spans its chars"
      (let [hello (first (pdf/words d))]
        (is (< (:x0 hello) (:x1 hello)))
        (is (< (:top hello) (:bottom hello)))))))

(deftest text-reconstruction
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (is (= "Hello PDF" (pdf/text d)))))

(deftest bbox-filtering
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (let [hello (first (filter #(= "Hello" (:text %)) (pdf/words d)))
          box [(- (:x0 hello) 1) (- (:top hello) 1)
               (+ (:x1 hello) 1) (+ (:bottom hello) 1)]]
      (testing "cropping to one word's region excludes the other"
        (is (= ["Hello"] (mapv :text (pdf/words d {:bbox box}))))))))

(deftest page-scoped-extraction
  (pdf/with-pdf [d (fix/multi-page-pdf ["one" "two" "three"])]
    (testing "extraction limited to a single page"
      (is (= ["two"] (mapv :text (pdf/words d {:page 2}))))
      (is (= [2] (distinct (map :page-number (pdf/chars d {:page 2}))))))))
