(ns pdfplumber.signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.document :as document]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.signature :as signature])
  (:import [java.io ByteArrayOutputStream]
           [java.math BigInteger]
           [java.security KeyPairGenerator]
           [java.time Instant]
           [java.util Arrays Calendar Date GregorianCalendar TimeZone]
           [org.bouncycastle.asn1.x500 X500Name]
           [org.bouncycastle.cert X509CertificateHolder]
           [org.bouncycastle.cert.jcajce JcaX509CertificateConverter
            JcaX509v3CertificateBuilder]
           [org.bouncycastle.cms CMSSignedDataGenerator CMSProcessableByteArray]
           [org.bouncycastle.cms.jcajce JcaSignerInfoGeneratorBuilder]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.bouncycastle.util.encoders Hex]
           [org.bouncycastle.operator.jcajce JcaContentSignerBuilder
            JcaDigestCalculatorProviderBuilder]
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

(defn- cms-signature [^bytes content]
  (let [provider (BouncyCastleProvider.)
        key-generator (doto (KeyPairGenerator/getInstance "RSA" provider)
                        (.initialize 2048))
        key-pair (.generateKeyPair key-generator)
        name (X500Name. "CN=Test Signer")
        now (Date.)
        builder (JcaX509v3CertificateBuilder. name BigInteger/ONE now
                                              (Date. (+ (.getTime now) 60000))
                                              name (.getPublic key-pair))
        signer (.. (JcaContentSignerBuilder. "SHA256withRSA")
                   (setProvider provider)
                   (build (.getPrivate key-pair)))
        certificate (.. (JcaX509CertificateConverter.)
                        (setProvider provider)
                        (getCertificate (.build builder signer)))
        digest-provider (.. (JcaDigestCalculatorProviderBuilder.)
                            (setProvider provider)
                            (build))
        signer-info (.. (JcaSignerInfoGeneratorBuilder. digest-provider)
                        (build signer certificate))
        generator (doto (CMSSignedDataGenerator.)
                    (.addSignerInfoGenerator signer-info)
                    (.addCertificate (X509CertificateHolder. (.getEncoded certificate))))]
    (.getEncoded (.generate generator (CMSProcessableByteArray. content) false))))

(deftest valid-cms-signature-test
  (let [content (.getBytes "signed bytes" "UTF-8")
        signature-value (cms-signature content)
        sig (doto (PDSignature.)
              (.setByteRange (int-array [0 (alength content) (alength content) 0]))
              (.setContents signature-value))
        verify-cms (ns-resolve 'pdfplumber.signature 'verify-cms)
        result (verify-cms sig content)]
    (is (= "CN=Test Signer" (:signer-identity result)))
    (is (true? (:digest-valid? result)))
    (is (= :untrusted (:trust-status result)))
    (is (false? (:revocation-checked? result)))
    (is (= 1 (count (:certificate-chain result))))))

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
          ;; This fixture's fabricated ByteRange gap does not match its Contents span.
          (is (false? (:covers-whole-document? result))))
        (testing "verification is explicit for an invalid signature"
          (is (false? (:digest-valid? result)))
          (is (= :invalid (:trust-status result)))
          (is (contains? result :certificate-chain))
          (is (contains? result :signer-identity)))
        (is (true? (signature/signed? doc)))))))

(deftest genuine-whole-document-signature-test
  (let [contents (byte-array 32)
        prefix "signed-prefix"
        serialized (str "<" (Hex/toHexString contents) ">")
        suffix "unsigned-suffix"
        source (.getBytes (str prefix serialized suffix) "ISO-8859-1")
        signature (doto (PDSignature.)
                    (.setContents contents)
                    (.setByteRange (int-array [0 (count prefix)
                                               (+ (count prefix) (count serialized))
                                               (count suffix)])))
        signature-map (ns-resolve 'pdfplumber.signature 'signature-map)]
    (is (true? (:covers-whole-document? (signature-map signature source))))))
