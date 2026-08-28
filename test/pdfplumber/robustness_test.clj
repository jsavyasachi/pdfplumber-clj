(ns pdfplumber.robustness-test
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.reducible :as reducible]))

(deftest bounded-malformed-input-fuzz
  (testing "deterministic malformed inputs return the documented parse error"
    (doseq [n (range 32)]
      (let [bytes (byte-array (concat [37 80 68 70 45 49 46 55 10]
                                      (repeat (+ 1 n) (mod (* n 37) 255))))]
        (try
          (pdf/open-pdf bytes)
          (is false (str "malformed input unexpectedly opened for case " n))
          (catch clojure.lang.ExceptionInfo e
            (is (= :parse-failed (:pdfplumber/error (ex-data e))))))))))

(deftest large-generated-regression
  (pdf/with-pdf [doc (fix/multi-page-pdf (map #(str "page-" %) (range 64)))]
    (is (= 64 (count (pdf/pages doc))))
    (is (= "page-63" (pdf/text doc {:page 64})))
    (is (= 64 (count (into [] (reducible/reducible-words doc)))))))

(deftest image-heavy-regression
  (pdf/with-pdf [doc (fix/image-heavy-pdf)]
    (is (= 16 (count (pdf/images doc))))
    (is (every? #(= [8 8] (mapv % [:width :height]))
                (pdf/images doc)))))
