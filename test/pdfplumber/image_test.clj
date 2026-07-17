(ns pdfplumber.image-test
  (:refer-clojure :exclude [copy reset])
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.document :as document]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.image :as image]
            [pdfplumber.page :as page])
  (:import [java.awt Color]
           [java.awt.image BufferedImage]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(System/setProperty "java.awt.headless" "true")

(defn- image-size [pi]
  (let [^BufferedImage raster (:image pi)]
    [(.getWidth raster) (.getHeight raster)]))

(deftest renders-page-at-requested-resolution
  (with-open [doc (document/open-pdf (fix/simple-text-pdf))]
    (let [pi (image/to-image doc {:page 1 :resolution 144})]
      (is (image/page-image? pi))
      (is (instance? BufferedImage (:image pi)))
      (is (= [1224 1584] (image-size pi)))
      (is (== 2.0 (:scale pi)))
      (is (= [0.0 0.0] (:root pi))))))

(deftest renders-and-draws-on-cropped-page
  (with-open [doc (document/open-pdf (fix/simple-text-pdf))]
    (let [view (page/crop doc [60 70 160 170] {:page 1})
          pi (image/to-image view)
          ^BufferedImage before (:image (image/copy pi))]
      (is (= [100 100] (image-size pi)))
      (is (= [60 70] (:root pi)))
      (is (image/page-image?
           (image/draw-circle pi [80 90]
                              {:radius 3 :fill [0 0 255 255] :stroke nil})))
      (is (not= (.getRGB before 20 20)
                (.getRGB ^BufferedImage (:image pi) 20 20))))))

(deftest drawing-operations-are-chainable
  (with-open [doc (document/open-pdf (fix/simple-text-pdf))]
    (let [pi (image/to-image doc)
          operations [(fn [x] (image/draw-line x [[10 10] [50 50]]))
                      (fn [x] (image/draw-line x {:x0 15 :top 20 :x1 55 :bottom 20}))
                      (fn [x] (image/draw-vline x 30 {:stroke Color/BLUE}))
                      (fn [x] (image/draw-hline x 40))
                      (fn [x] (image/draw-rect x [60 60 100 100]))
                      (fn [x] (image/draw-rects x [{:x0 110 :top 60 :x1 150 :bottom 100}]))
                      (fn [x] (image/draw-circle x [170 80]))
                      (fn [x] (image/draw-circles x [[190 80] [210 80]]))]]
      (is (every? image/page-image? (map #(% pi) operations)))
      (is (image/page-image? (image/reset pi)))
      (is (not (identical? (:image pi) (:image (image/reset pi))))))))

(deftest outlines-extracted-text
  (with-open [doc (document/open-pdf (fix/simple-text-pdf))]
    (let [pi (image/to-image doc {:page 1})]
      (is (image/page-image? (image/outline-words pi {:stroke [0 255 0]})))
      (is (image/page-image? (image/outline-chars pi {:stroke [0 0 255]}))))))

(deftest debugs-detected-tables
  (with-open [doc (document/open-pdf (fix/table-pdf))]
    (is (image/page-image?
         (image/debug-tablefinder (image/to-image doc {:page 1})
                                  {:strategy :lines})))))

(deftest saves-png-and-copies-raster
  (with-open [doc (document/open-pdf (fix/simple-text-pdf))]
    (let [dir (.toFile (Files/createTempDirectory
                        "pdfplumber-image-"
                        (make-array FileAttribute 0)))
          out (java.io.File. dir "page.png")
          pi (image/to-image doc)
          duplicate (image/copy pi)]
      (try
        (testing "copy owns an independent raster"
          (is (image/page-image? duplicate))
          (is (not (identical? (:image pi) (:image duplicate)))))
        (testing "save emits a PNG"
          (is (= (.getPath out) (image/save pi (.getPath out))))
          (is (.isFile out))
          (is (pos? (.length out)))
          (with-open [in (java.io.FileInputStream. out)]
            (is (= [0x89 0x50 0x4e 0x47]
                   (mapv (fn [_] (.read in)) (range 4))))))
        (finally
          (.delete out)
          (.delete dir))))))
