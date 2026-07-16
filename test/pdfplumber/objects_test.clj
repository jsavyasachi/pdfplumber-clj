(ns pdfplumber.objects-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]))

(defn- approx= [a b] (<= (Math/abs (- (double a) (double b))) 1.0))

(defn- find-line [objs orientation]
  (first (filter #(and (= :line (:type %)) (= orientation (:orientation %))) objs)))

(deftest line-extraction
  (pdf/with-pdf [d (fix/ruled-pdf)]
    (let [lines (pdf/objects d {:types #{:line}})]
      (testing "horizontal and vertical rules are found and classified"
        (is (= 2 (count lines)))
        (let [h (find-line lines :horizontal)
              v (find-line lines :vertical)]
          (is (some? h))
          (is (some? v))
          (testing "horizontal rule geometry (top-left coords)"
            (is (approx= 72.0 (:x0 h)))
            (is (approx= 540.0 (:x1 h)))
            (is (approx= 92.0 (:top h)))
            (is (approx= 92.0 (:bottom h))))
          (testing "vertical rule geometry"
            (is (approx= 72.0 (:x0 v)))
            (is (approx= 72.0 (:x1 v)))
            (is (approx= 92.0 (:top v)))
            (is (approx= 292.0 (:bottom v)))))))))

(deftest rect-extraction
  (pdf/with-pdf [d (fix/ruled-pdf)]
    (let [rects (pdf/objects d {:types #{:rect}})]
      (testing "the stroked rectangle is captured as one rect"
        (is (= 1 (count rects)))
        (let [r (first rects)]
          (is (approx= 100.0 (:x0 r)))
          (is (approx= 300.0 (:x1 r)))
          (is (approx= 292.0 (:top r)))
          (is (approx= 392.0 (:bottom r))))))))

(deftest type-and-bbox-filtering
  (pdf/with-pdf [d (fix/ruled-pdf)]
    (testing ":types filter restricts object kinds"
      (is (every? #(= :line (:type %)) (pdf/objects d {:types #{:line}})))
      (is (= #{:line :rect} (set (map :type (pdf/objects d))))))
    (testing ":bbox keeps only intersecting objects"
      ;; a box around the horizontal rule only (top-left ~ y 92)
      (let [near-top (pdf/objects d {:bbox [0 80 612 100]})]
        (is (pos? (count near-top)))
        (is (every? #(<= (:top %) 100) near-top))))))

(deftest image-extraction
  (pdf/with-pdf [d (fix/image-pdf)]
    (let [images (pdf/images d)
          image (first images)]
      (testing "drawn image geometry and metadata"
        (is (= 1 (count images)))
        (is (= :image (:type image)))
        (is (= :image (:object-type image)))
        (is (= 2 (:width image)))
        (is (= 3 (:height image)))
        (is (= "DeviceRGB" (:colorspace image)))
        (is (= 8 (:bits image)))
        (is (true? (:srgb? image)))
        (is (false? (:mask? image)))
        (is (false? (:smask? image)))
        (is (every? #(contains? image %)
                    [:x0 :top :x1 :bottom :colorspace :bits
                     :srgb? :mask? :smask? :page-number]))
        (is (approx= 100 (:x0 image)))
        (is (approx= 140 (:x1 image)))
        (is (approx= 162 (:top image)))
        (is (approx= 192 (:bottom image)))
        (is (not (contains? image :bytes))))
      (testing "images are part of the general object stream"
        (is (= [image] (pdf/objects d {:types #{:image}}))))
      (testing "decoded PNG bytes are opt-in"
        (let [decoded (first (pdf/images d {:include-image-data? true}))]
          (is (bytes? (:bytes decoded)))
          (is (= [-119 80 78 71]
                 (mapv int (take 4 (:bytes decoded))))))))))

(deftest no-images
  (pdf/with-pdf [d (fix/simple-text-pdf)]
    (is (= [] (pdf/images d)))))
