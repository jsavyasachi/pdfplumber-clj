(ns pdfplumber.attachments-test
  (:require [clojure.test :refer [deftest is]]
            [pdfplumber.attachments :as attachments]
            [pdfplumber.document :as document]
            [pdfplumber.fixtures :as fixtures])
  (:import [java.io ByteArrayInputStream]
           [java.util Arrays]
           [org.apache.pdfbox.pdmodel
            PDDocument PDDocumentNameDictionary PDEmbeddedFilesNameTreeNode PDPage]
           [org.apache.pdfbox.pdmodel.common.filespecification
            PDComplexFileSpecification PDEmbeddedFile]))

(set! *warn-on-reflection* true)

(defn- document-with-attachment ^PDDocument [^bytes data]
  (let [doc (PDDocument.)
        embedded (PDEmbeddedFile. doc (ByteArrayInputStream. data))
        spec (PDComplexFileSpecification.)
        tree (PDEmbeddedFilesNameTreeNode.)
        child (PDEmbeddedFilesNameTreeNode.)
        names (PDDocumentNameDictionary. (.getDocumentCatalog doc))]
    (.addPage doc (PDPage.))
    (.setSubtype embedded "text/plain")
    (.setSize embedded (alength data))
    (.setFile spec "hello.txt")
    (.setEmbeddedFile spec embedded)
    (.setNames child {"hello.txt" spec})
    (.setKids tree [child])
    (.setEmbeddedFiles names tree)
    (.setNames (.getDocumentCatalog doc) names)
    doc))

(deftest embedded-attachments
  (let [data (.getBytes "hello" "UTF-8")]
    (with-open [doc (document-with-attachment data)]
      (let [without-data (first (attachments/attachments doc))
            with-data (first (attachments/attachments doc {:include-data? true}))]
        (is (= {:name "hello.txt" :size 5 :mime-type "text/plain"}
               without-data))
        (is (not (contains? without-data :bytes)))
        (is (Arrays/equals data ^bytes (:bytes with-data)))))))

(deftest absent-attachments
  (with-open [doc (document/open-pdf (fixtures/simple-text-pdf))]
    (is (= [] (attachments/attachments doc)))))
