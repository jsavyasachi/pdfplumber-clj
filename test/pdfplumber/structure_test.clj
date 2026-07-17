(ns pdfplumber.structure-test
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.structure :as structure])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure
            PDMarkedContentReference PDStructureElement PDStructureTreeRoot]))

(set! *warn-on-reflection* true)

(defn- tagged-doc ^PDDocument []
  (let [doc (PDDocument.)
        page-1 (PDPage. PDRectangle/LETTER)
        page-2 (PDPage. PDRectangle/LETTER)
        root (PDStructureTreeRoot.)
        section (PDStructureElement. "Sect" root)
        paragraph (PDStructureElement. "P" section)
        mcr (PDMarkedContentReference.)]
    (.addPage doc page-1)
    (.addPage doc page-2)
    (.setStructureTreeRoot (.getDocumentCatalog doc) root)
    (.setPage section page-1)
    (.setLanguage section "en-US")
    (.setAlternateDescription section "Section summary")
    (.setActualText section "Actual section")
    (.appendKid section (int 7))
    (.setPage paragraph page-2)
    (.setPage mcr page-2)
    (.setMCID mcr 11)
    (.appendKid paragraph mcr)
    (.appendKid section paragraph)
    (.appendKid root section)
    doc))

(deftest untagged-document-has-empty-structure
  (pdf/with-pdf [doc (fix/simple-text-pdf)]
    (is (= [] (structure/structure-tree doc)))
    (is (= [] (structure/page-structure-tree doc 1)))))

(deftest tagged-document-structure
  (with-open [doc (tagged-doc)]
    (let [tree (structure/structure-tree doc)
          section (first tree)
          paragraph (first (:children section))]
      (testing "element shape and metadata"
        (is (= 1 (count tree)))
        (is (= {:type "Sect"
                :mcids [7]
                :children [{:type "P"
                            :mcids [11]
                            :children []
                            :page-number 2}]
                :lang "en-US"
                :alt-text "Section summary"
                :actual-text "Actual section"
                :page-number 1}
               section))
        (is (string? (:type paragraph)))
        (is (vector? (:children paragraph)))
        (is (every? integer? (:mcids paragraph))))
      (testing "page extraction prunes unrelated content"
        (is (= [7] (:mcids (first (structure/page-structure-tree doc 1)))))
        (is (= [] (:children (first (structure/page-structure-tree doc 1)))))
        (is (= [11]
               (get-in (structure/page-structure-tree doc 2)
                       [0 :children 0 :mcids])))))))
