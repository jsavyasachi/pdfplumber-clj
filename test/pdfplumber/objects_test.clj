(ns pdfplumber.objects-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix])
  (:import [org.apache.pdfbox.cos COSName]
           [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.interactive.form PDAcroForm PDTextField]))

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

(deftest rich-graphics-records
  (pdf/with-pdf [d (fix/ruled-pdf)]
    (doseq [o (pdf/objects d)]
      (testing (str "complete " (:type o) " record")
        (is (every? #(contains? o %)
                    [:x0 :x1 :y0 :y1 :top :bottom :width :height :doctop
                     :page-number :linewidth :stroking-color
                     :non-stroking-color :object-type]))
        (is (= (:type o) (:object-type o)))
        (is (== (- (:x1 o) (:x0 o)) (:width o)))
        (is (== (- (:bottom o) (:top o)) (:height o)))
        (is (== (- 792.0 (:bottom o)) (:y0 o)))
        (is (== (- 792.0 (:top o)) (:y1 o)))
        (is (== (:top o) (:doctop o)))
        (is (== 1.0 (:linewidth o)))))))

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

(deftest typed-collections-and-edges
  (pdf/with-pdf [d (fix/ruled-pdf)]
    (let [lines-var (ns-resolve 'pdfplumber.core 'lines)
          rects-var (ns-resolve 'pdfplumber.core 'rects)
          curves-var (ns-resolve 'pdfplumber.core 'curves)
          grouped-var (ns-resolve 'pdfplumber.core 'objects-by-type)
          edges-var (ns-resolve 'pdfplumber.core 'edges)
          horizontal-var (ns-resolve 'pdfplumber.core 'horizontal-edges)
          vertical-var (ns-resolve 'pdfplumber.core 'vertical-edges)]
      (is (every? some? [lines-var rects-var curves-var grouped-var edges-var
                         horizontal-var vertical-var]))
      (when (every? some? [lines-var rects-var curves-var grouped-var edges-var
                           horizontal-var vertical-var])
        (is (= 2 (count (lines-var d))))
        (is (= 1 (count (rects-var d))))
        (is (= [] (curves-var d)))
        (is (= #{:line :rect} (set (keys (grouped-var d)))))
        (let [edges (edges-var d)]
          (is (= 6 (count edges)))
          (is (= edges (vec (concat (horizontal-var d) (vertical-var d)))))
          (is (every? #(contains? #{:horizontal :vertical} (:orientation %)) edges))
          (is (= 4 (count (filter #(= :rect-edge (:object-type %)) edges))))))))
  (pdf/with-pdf [d (fix/curve-pdf)]
    (let [curves-var (ns-resolve 'pdfplumber.core 'curves)
          edges-var (ns-resolve 'pdfplumber.core 'edges)]
      (when (and curves-var edges-var)
        (is (= 1 (count (curves-var d))))
        (is (seq (:pts (first (curves-var d)))))
        (is (seq (filter #(= :curve-edge (:object-type %)) (edges-var d))))))))

(deftest annotations-and-hyperlinks
  (pdf/with-pdf [d (fix/annotations-pdf)]
    (let [annots-var (ns-resolve 'pdfplumber.core 'annots)
          hyperlinks-var (ns-resolve 'pdfplumber.core 'hyperlinks)]
      (is (every? some? [annots-var hyperlinks-var]))
      (when (and annots-var hyperlinks-var)
        (let [annots (annots-var d)
              links (hyperlinks-var d)
              link (first links)]
          (is (= 2 (count annots)))
          (is (= 1 (count links)))
          (is (= "https://example.com" (:uri link)))
          (is (= :annot (:object-type link)))
          (is (= "Link" (:subtype link)))
          (is (approx= 72 (:x0 link)))
          (is (approx= 172 (:x1 link)))
          (is (approx= 122 (:top link)))
          (is (approx= 142 (:bottom link)))
          (is (= "review this" (:contents (second annots))))
          (is (= "Editor" (:title (second annots)))))))))

(defn- filled-text-field-doc ^PDDocument []
  (let [doc (PDDocument.)
        page (PDPage. PDRectangle/LETTER)
        form (PDAcroForm. doc)
        field (PDTextField. form)
        widget (first (.getWidgets field))]
    (.addPage doc page)
    (.setAcroForm (.getDocumentCatalog doc) form)
    (.setPartialName field "customer")
    (.setString (.getCOSObject field) COSName/V "Ada Lovelace")
    (.setRectangle widget (PDRectangle. (float 72) (float 650) (float 160) (float 20)))
    (.setPage widget page)
    (.setAnnotations page [widget])
    (.setFields form [field])
    doc))

(deftest widget-annotations-include-field-values
  (with-open [doc (filled-text-field-doc)]
    (let [widget (first (pdf/annots doc))]
      (is (= "Widget" (:subtype widget)))
      (is (= "customer" (:field-name widget)))
      (is (= "Ada Lovelace" (:field-value widget)))
      (is (= :text (:field-type widget)))))
  (pdf/with-pdf [doc (fix/annotations-pdf)]
    (is (every? #(not (contains? % :field-name)) (pdf/annots doc)))))
