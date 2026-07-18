(ns pdfplumber.signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.document :as document]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.signature :as signature])
  (:import [java.io ByteArrayOutputStream]
           [java.time Instant]
           [java.util Arrays Calendar GregorianCalendar TimeZone]
           [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.interactive.digitalsignature PDSignature]
           [org.apache.pdfbox.pdmodel.interactive.form PDAcroForm PDSignatureField]))

(set! *warn-on-reflection* true)

(def ^:private signing-time (Instant/parse "2026-07-17T12:34:56Z"))

(defn- calendar-at ^Calendar [^Instant instant]
  (doto (GregorianCalendar. (TimeZone/getTimeZone "UTC"))
    (.setTimeInMillis (.toEpochMilli instant))))

(defn- fake-signed-pdf-with-length ^bytes [length]
  (with-open [doc (PDDocument.)
              out (ByteArrayOutputStream.)]
    (.addPage doc (PDPage.))
    (let [acro-form (PDAcroForm. doc)
          field (PDSignatureField. acro-form)
          signature (doto (PDSignature.)
                      (.setName "Ada Signer")
                      (.setReason "Approved")
                      (.setLocation "London")
                      (.setContactInfo "ada@example.test")
                      (.setSignDate (calendar-at signing-time))
                      (.setFilter PDSignature/FILTER_ADOBE_PPKLITE)
                      (.setSubFilter PDSignature/SUBFILTER_ADBE_PKCS7_DETACHED)
                      (.setContents (byte-array 32))
                      (.setByteRange (int-array [0 64 128 (- length 128)])))]
      (.setAcroForm (.getDocumentCatalog doc) acro-form)
      (.setValue field signature)
      (.add (.getFields acro-form) field)
      (.save doc out)
      (let [^bytes pdf (.toByteArray out)]
        (when (> (alength pdf) length)
          (throw (ex-info "Synthetic PDF exceeded target length"
                          {:target length :actual (alength pdf)})))
        (let [^bytes padded (Arrays/copyOf pdf (int length))]
          (Arrays/fill padded (alength pdf) (alength padded) (byte 32))
          padded)))))

(defn- fake-signed-pdf ^bytes []
  (fake-signed-pdf-with-length 2048))

(deftest unsigned-document-test
  (with-open [doc (document/open-pdf (fix/simple-text-pdf))]
    (is (= [] (signature/signatures doc)))
    (is (false? (signature/signed? doc)))))

(deftest signature-metadata-test
  (let [^bytes pdf (fake-signed-pdf)]
    (with-open [doc (document/open-pdf pdf)]
      (let [result (first (signature/signatures doc))]
        (testing "present signature metadata"
          (is (= "Ada Signer" (:name result)))
          (is (= "Approved" (:reason result)))
          (is (= "London" (:location result)))
          (is (= "ada@example.test" (:contact-info result)))
          (is (= signing-time (:signing-time result)))
          (is (= "adbe.pkcs7.detached" (:sub-filter result)))
          (is (= "Adobe.PPKLite" (:filter result))))
        (testing "byte-range integrity signal"
          (is (= [0 64 128 (- (alength pdf) 128)]
                 (:byte-range result)))
          (is (true? (:covers-whole-document? result))))
        (is (true? (signature/signed? doc)))))))
