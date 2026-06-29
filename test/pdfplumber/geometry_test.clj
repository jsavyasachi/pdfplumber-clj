(ns pdfplumber.geometry-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.geometry :as g]))

;; Public bbox convention: [x0 top x1 bottom], top-left origin, top <= bottom.

(deftest flip-y-test
  (testing "flip-y maps between bottom-origin and top-origin about page height"
    (is (== 792 (g/flip-y 792 0)))
    (is (== 0 (g/flip-y 792 792)))
    (is (== 692 (g/flip-y 792 100))))
  (testing "flip-y is an involution"
    (is (== 123.5 (g/flip-y 792 (g/flip-y 792 123.5))))))

(deftest pdfbox-rect->bbox
  (testing "PDFBox lower-left rect (x y w h) -> top-left [x0 top x1 bottom]"
    ;; object 20pt tall sitting near the top of a 792pt page
    (is (= [72.0 80.0 172.0 100.0]
           (g/pdfbox-rect->bbox 792 72.0 692.0 100.0 20.0))))
  (testing "object flush with the page bottom"
    (is (= [0.0 782.0 10.0 792.0]
           (g/pdfbox-rect->bbox 792 0.0 0.0 10.0 10.0)))))

(deftest dimensions-test
  (is (== 100 (g/bbox-width [72 80 172 100])))
  (is (== 20 (g/bbox-height [72 80 172 100])))
  (is (== 0 (g/bbox-width [5 5 5 5]))))

(deftest intersects?-test
  (testing "overlapping boxes intersect"
    (is (g/intersects? [0 0 10 10] [5 5 15 15])))
  (testing "edge-touching boxes do not intersect (zero overlap area)"
    (is (not (g/intersects? [0 0 10 10] [10 10 20 20])))
    (is (not (g/intersects? [0 0 10 10] [10 0 20 10]))))
  (testing "disjoint boxes do not intersect"
    (is (not (g/intersects? [0 0 10 10] [20 20 30 30])))))

(deftest contains?-test
  (testing "outer fully contains inner (boundaries inclusive)"
    (is (g/contains? [0 0 100 100] [10 10 20 20]))
    (is (g/contains? [0 0 100 100] [0 0 100 100])))
  (testing "partial overlap is not containment"
    (is (not (g/contains? [0 0 100 100] [50 50 150 60])))))

(deftest intersection-test
  (testing "overlap region"
    (is (= [5 5 10 10] (g/intersection [0 0 10 10] [5 5 15 15]))))
  (testing "disjoint -> nil"
    (is (nil? (g/intersection [0 0 10 10] [20 20 30 30])))
    (is (nil? (g/intersection [0 0 10 10] [10 10 20 20])))))

(deftest within?-test
  (testing "point inside bbox (inclusive boundary)"
    (is (g/within? [0 0 10 10] [5 5]))
    (is (g/within? [0 0 10 10] [10 10]))
    (is (g/within? [0 0 10 10] [0 0])))
  (testing "point outside"
    (is (not (g/within? [0 0 10 10] [15 5])))
    (is (not (g/within? [0 0 10 10] [5 -1])))))

(deftest center-test
  (is (= [5.0 10.0] (g/center [0 0 10 20])))
  (is (= [122.0 90.0] (g/center [72 80 172 100]))))
