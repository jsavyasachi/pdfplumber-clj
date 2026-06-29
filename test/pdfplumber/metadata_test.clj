(ns pdfplumber.metadata-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]))

(deftest metadata-extraction
  (testing "document information fields"
    (pdf/with-pdf [d (fix/pdf-with-metadata {:title "Q2 Statement"
                                             :author "Acme Bank"
                                             :subject "Account"})]
      (let [m (pdf/metadata d)]
        (is (= "Q2 Statement" (:title m)))
        (is (= "Acme Bank" (:author m)))
        (is (= "Account" (:subject m)))
        (is (= 1 (:page-count m))))))
  (testing "absent fields are omitted, page-count always present"
    (pdf/with-pdf [d (fix/simple-text-pdf)]
      (let [m (pdf/metadata d)]
        (is (= 1 (:page-count m)))
        (is (not (contains? m :title)))))))

(deftest pages-enumeration
  (testing "single page geometry"
    (pdf/with-pdf [d (fix/simple-text-pdf)]
      (let [[p & more] (pdf/pages d)]
        (is (nil? more))
        (is (= 1 (:page-number p)))
        (is (== 612.0 (:width p)))
        (is (== 792.0 (:height p)))
        (is (= 0 (:rotation p)))
        (is (= [0.0 0.0 612.0 792.0] (:bbox p))))))
  (testing "multi-page numbering is 1-based"
    (pdf/with-pdf [d (fix/multi-page-pdf ["one" "two" "three"])]
      (is (= [1 2 3] (mapv :page-number (pdf/pages d)))))))

(deftest page-lookup
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (testing "1-based lookup"
      (is (= 1 (:page-number (pdf/page d 1)))))
    (testing "out-of-range -> :page-not-found with context"
      (doseq [n [0 2 99]]
        (try (pdf/page d n)
             (is false "expected throw")
             (catch clojure.lang.ExceptionInfo e
               (is (= :page-not-found (:pdfplumber/error (ex-data e))))
               (is (= n (:page (ex-data e))))
               (is (= 1 (:page-count (ex-data e))))))))))
