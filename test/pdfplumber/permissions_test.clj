(ns pdfplumber.permissions-test
  (:require [clojure.test :refer [deftest is]]
            [pdfplumber.document :as document]
            [pdfplumber.fixtures :as fixtures]
            [pdfplumber.permissions :as permissions])
  (:import [java.io ByteArrayOutputStream]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.encryption
            AccessPermission StandardProtectionPolicy]))

(set! *warn-on-reflection* true)

(defn- restricted-pdf ^bytes []
  (with-open [doc (PDDocument.)]
    (.addPage doc (PDPage.))
    (let [access (doto (AccessPermission.)
                   (.setCanPrint false)
                   (.setCanModify false))
          policy (StandardProtectionPolicy. "owner" "user" access)
          output (ByteArrayOutputStream.)]
      (.setEncryptionKeyLength policy 128)
      (.protect doc policy)
      (.save doc output)
      (.toByteArray output))))

(deftest restricted-permissions
  (with-open [doc (Loader/loadPDF (restricted-pdf) "user")]
    (is (= {:encrypted? true
            :can-print? false
            :can-modify? false
            :can-extract-content? true
            :can-extract-for-accessibility? true
            :can-assemble-document? true
            :can-fill-in-form? true
            :can-modify-annotations? true
            :can-print-faithful? true
            :key-length 128
            :security-handler "Standard"}
           (permissions/permissions doc)))))

(deftest unrestricted-permissions
  (with-open [doc (document/open-pdf (fixtures/simple-text-pdf))]
    (is (= {:encrypted? false
            :can-print? true
            :can-modify? true
            :can-extract-content? true
            :can-extract-for-accessibility? true
            :can-assemble-document? true
            :can-fill-in-form? true
            :can-modify-annotations? true
            :can-print-faithful? true}
           (permissions/permissions doc)))))
