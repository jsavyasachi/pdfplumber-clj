(ns pdfplumber.text-transitive-test
  (:require [clojure.test :refer [deftest is]]
            [pdfplumber.text :as text]))

(deftest clusters-transitive-top-shifts
  (let [chars (map-indexed (fn [i letter]
                             {:text (str letter)
                              :x0 (* 5.0 i) :x1 (* 5.0 (inc i))
                              :top (* 3.0 i) :bottom (+ 10.0 (* 3.0 i))
                              :y0 20.0 :y1 30.0 :upright true})
                           "ABC")
        word-data (ns-resolve 'pdfplumber.text 'word-data-from-chars)]
    (is (= 1 (count (:lines (word-data chars {:cluster-by-top true
                                              :cluster-transitively true})))))
    (is (= 2 (count (:lines (word-data chars {:cluster-by-top true})))))))
