(ns pdfplumber.ocr
  "A dependency-free seam for caller-supplied OCR engines.

   This namespace does not implement OCR. An engine receives a `PageImage` and
   returns the text representation chosen by its caller.")

(defprotocol PageImageToText
  "Convert a rasterized `PageImage` to caller-defined text.

   Implementations own the OCR engine, its configuration, and its return type.
   The library deliberately provides no OCR engine or OCR dependency."
  (text-from-page-image [engine page-image]))

(defn page-image->text
  "Invoke a `PageImageToText` engine on `page-image`.

   Throws `ex-info` with `:pdfplumber/error :invalid-ocr-engine` when `engine`
   does not implement the protocol."
  [engine page-image]
  (if (satisfies? PageImageToText engine)
    (text-from-page-image engine page-image)
    (throw (ex-info "OCR engine must implement PageImageToText"
                    {:pdfplumber/error :invalid-ocr-engine
                     :engine-class (some-> engine class .getName)}))))
