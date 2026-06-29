(ns pdfplumber.page-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]))

(deftest crop-restricts-text
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (let [hello (first (filter #(= "Hello" (:text %)) (pdf/words d)))
          box [(- (:x0 hello) 1) (- (:top hello) 1) (+ (:x1 hello) 1) (+ (:bottom hello) 1)]
          view (pdf/crop-page d {:page 1 :bbox box})]
      (testing "a view is recognized and restricts extraction"
        (is (pdf/page-view? view))
        (is (= ["Hello"] (mapv :text (pdf/words view))))
        (is (= "Hello" (pdf/text view)))))))

(deftest crop-restricts-objects
  (pdf/with-pdf [d (fix/ruled-pdf)]
    (let [view (pdf/crop-page d {:page 1 :bbox [0 80 612 100]})]
      (testing "objects limited to the cropped region near the top rule"
        (let [objs (pdf/objects view)]
          (is (pos? (count objs)))
          (is (every? #(<= (:top %) 100) objs)))))))

(deftest explicit-opts-override-view
  (pdf/with-pdf [d (fix/multi-page-pdf ["one" "two" "three"])]
    (let [view (pdf/crop-page d {:page 1 :bbox nil})]
      (testing "an explicit :page overrides the view's page"
        (is (= ["two"] (mapv :text (pdf/words view {:page 2}))))))))
