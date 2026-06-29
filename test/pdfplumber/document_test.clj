(ns pdfplumber.document-test
  (:require [clojure.test :refer [deftest testing is]]
            [pdfplumber.core :as pdf]
            [pdfplumber.document :as document]
            [pdfplumber.fixtures :as fix])
  (:import [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.cos COSDocument]
           [java.io File ByteArrayInputStream]))

(defn- closed? [^PDDocument d]
  (.isClosed ^COSDocument (.getDocument d)))

(defn- temp-pdf ^File [^bytes bs]
  (let [f (File/createTempFile "pdfplumber-fixture" ".pdf")]
    (.deleteOnExit f)
    (with-open [o (java.io.FileOutputStream. f)] (.write o bs))
    f))

(deftest open-pdf-sources
  (let [bs (fix/simple-text-pdf)]
    (testing "byte array"
      (with-open [d (document/open-pdf bs)]
        (is (instance? PDDocument d))
        (is (= 1 (.getNumberOfPages d)))))
    (testing "path string"
      (let [f (temp-pdf bs)]
        (with-open [d (document/open-pdf (.getPath f))]
          (is (= 1 (.getNumberOfPages d))))))
    (testing "java.io.File"
      (with-open [d (document/open-pdf (temp-pdf bs))]
        (is (= 1 (.getNumberOfPages d)))))
    (testing "input stream"
      (with-open [d (document/open-pdf (ByteArrayInputStream. bs))]
        (is (= 1 (.getNumberOfPages d)))))))

(deftest error-model
  (testing "unsupported source type -> :invalid-input"
    (is (thrown? clojure.lang.ExceptionInfo (document/open-pdf 42)))
    (try (document/open-pdf 42)
         (catch clojure.lang.ExceptionInfo e
           (is (= :invalid-input (:pdfplumber/error (ex-data e)))))))
  (testing "missing file -> :invalid-input"
    (try (document/open-pdf "/no/such/file.pdf")
         (catch clojure.lang.ExceptionInfo e
           (is (= :invalid-input (:pdfplumber/error (ex-data e))))
           (is (= "/no/such/file.pdf" (:path (ex-data e)))))))
  (testing "non-PDF bytes -> :parse-failed"
    (try (document/open-pdf (.getBytes "not a pdf at all"))
         (catch clojure.lang.ExceptionInfo e
           (is (= :parse-failed (:pdfplumber/error (ex-data e)))))))
  (testing "encrypted PDF -> :encrypted-pdf"
    (try (document/open-pdf (fix/encrypted-pdf))
         (catch clojure.lang.ExceptionInfo e
           (is (= :encrypted-pdf (:pdfplumber/error (ex-data e))))))))

(deftest with-pdf-lifecycle
  (let [bs (fix/simple-text-pdf)
        captured (atom nil)
        result (pdf/with-pdf [d (ByteArrayInputStream. bs)]
                 (reset! captured d)
                 (.getNumberOfPages ^PDDocument d))]
    (testing "returns body value"
      (is (= 1 result)))
    (testing "closes the document on exit"
      (is (closed? @captured)))
    (testing "closes even when body throws"
      (let [c (atom nil)]
        (is (thrown? RuntimeException
                     (pdf/with-pdf [d (ByteArrayInputStream. bs)]
                       (reset! c d)
                       (throw (RuntimeException. "boom")))))
        (is (closed? @c))))))
