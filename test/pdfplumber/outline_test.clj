(ns pdfplumber.outline-test
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.document :as document]
            [pdfplumber.fixtures :as fixtures]
            [pdfplumber.outline :as outline])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline
            PDDocumentOutline PDOutlineItem]))

(set! *warn-on-reflection* true)

(deftest outline-tree
  (with-open [doc (PDDocument.)]
    (let [page-1 (PDPage.)
          page-2 (PDPage.)
          root (PDDocumentOutline.)
          chapter (doto (PDOutlineItem.)
                    (.setTitle "Chapter")
                    (.setDestination page-1))
          section (doto (PDOutlineItem.)
                    (.setTitle "Section")
                    (.setDestination page-2))]
      (.addPage doc page-1)
      (.addPage doc page-2)
      (.addLast chapter section)
      (.addLast root chapter)
      (.setDocumentOutline (.getDocumentCatalog doc) root)
      (is (= [{:title "Chapter"
               :page-number 1
               :children [{:title "Section"
                           :page-number 2
                           :children []}]}]
             (outline/outline doc))))))

(deftest absent-outline
  (with-open [doc (document/open-pdf (fixtures/simple-text-pdf))]
    (is (= [] (outline/outline doc)))))
