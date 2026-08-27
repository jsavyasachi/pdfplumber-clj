(ns pdfplumber.ocr-test
  (:require [clojure.test :refer [deftest is testing]]
            [pdfplumber.core :as pdf]
            [pdfplumber.fixtures :as fix]
            [pdfplumber.ocr :as ocr]))

(deftest page-text-status-identifies-ocr-candidates
  (let [status-var (ns-resolve 'pdfplumber.core 'page-text-status)
        candidate-var (ns-resolve 'pdfplumber.core 'ocr-candidate?)]
    (testing "the public detection API exists"
      (is (some? status-var))
      (is (some? candidate-var)))
    (when (and status-var candidate-var)
      (let [doc (pdf/open-pdf (fix/multi-page-pdf ["Digital text" ""]))]
        (try
          (let [status (status-var doc {:page 1})
                scanned-status (status-var doc {:page 2})]
            (is (= :text-layer (:status status)))
            (is (false? (candidate-var doc {:page 1})))
            (is (= :no-text-layer (:status scanned-status)))
            (is (true? (:ocr-candidate? scanned-status)))
            (is (true? (candidate-var doc {:page 2}))))
          (finally
            (.close doc)))))))

(deftest page-image-to-text-protocol-is-public
  (testing "the caller-supplied OCR seam is discoverable"
    (is (some? (ns-resolve 'pdfplumber.ocr 'PageImageToText)))
    (is (some? (ns-resolve 'pdfplumber.core 'page-image->text)))
    (let [doc (pdf/open-pdf (fix/simple-text-pdf))]
      (try
        (let [page-image (pdf/to-image doc {:page 1 :resolution 36})
              engine (reify ocr/PageImageToText
                       (text-from-page-image [_ image]
                         {:width (.getWidth (:image image))
                          :height (.getHeight (:image image))}))]
          (is (= {:width 306 :height 396}
                 (pdf/page-image->text engine page-image)))
          (try
            (pdf/page-image->text :not-an-engine page-image)
            (is false "an invalid OCR engine should be rejected")
            (catch clojure.lang.ExceptionInfo error
              (is (= :invalid-ocr-engine
                     (:pdfplumber/error (ex-data error)))))))
        (finally
          (.close doc))))))
