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

(deftest derived-page-operations
  (pdf/with-pdf [d (fix/ruled-pdf)]
    (let [crop-var (ns-resolve 'pdfplumber.core 'crop)
          within-var (ns-resolve 'pdfplumber.core 'within-bbox)
          outside-var (ns-resolve 'pdfplumber.core 'outside-bbox)
          filter-var (ns-resolve 'pdfplumber.core 'filter-page)]
      (is (every? some? [crop-var within-var outside-var filter-var]))
      (when (every? some? [crop-var within-var outside-var filter-var])
        (testing "absolute crop clips partial objects"
          (let [r (first (pdf/objects (crop-var d [150 300 250 350] {:page 1})))]
            (is (= :rect (:type r)))
            (is (== 150.0 (:x0 r)))
            (is (== 250.0 (:x1 r)))
            (is (== 300.0 (:top r)))
            (is (== 350.0 (:bottom r)))
            (is (== 100.0 (:width r)))
            (is (== 50.0 (:height r)))))
        (testing "relative crops resolve from the parent view origin"
          (let [parent (crop-var d [100 292 300 392] {:page 1})
                child (crop-var parent [25 25 75 75] {:relative true})
                r (first (pdf/objects child))]
            (is (= [125.0 317.0 175.0 367.0]
                   (mapv double [(:x0 r) (:top r) (:x1 r) (:bottom r)])))))
        (testing "within and outside use whole-object semantics"
          (is (= [] (pdf/objects (within-var d [150 300 250 350] {:page 1}))))
          (is (every? #(not= :rect (:type %))
                      (pdf/objects (outside-var d [90 280 310 400] {:page 1})))))
        (testing "predicate filtering composes with extraction"
          (let [view (filter-var d #(= :vertical (:orientation %)) {:page 1})]
            (is (= [:vertical] (mapv :orientation (pdf/objects view))))))))))
