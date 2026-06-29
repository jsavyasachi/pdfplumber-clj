(ns pdfplumber.fixtures
  "Deterministic fixture-PDF generators for tests (PDFBox writer side).
   Generating fixtures in code avoids opaque committed binaries and lets tests
   assert against known positions. Generated with PDFBox 3.0.x."
  (:import [org.apache.pdfbox.pdmodel PDDocument PDDocumentInformation PDPage PDPageContentStream]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.font PDType1Font Standard14Fonts$FontName]
           [org.apache.pdfbox.pdmodel.encryption AccessPermission StandardProtectionPolicy]
           [java.io ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn- helvetica ^PDType1Font []
  (PDType1Font. Standard14Fonts$FontName/HELVETICA))

(defn- ->bytes ^bytes [^PDDocument doc]
  (let [baos (ByteArrayOutputStream.)]
    (.save doc baos)
    (.toByteArray baos)))

(defn simple-text-pdf
  "Single US-Letter (612x792) page with `text` at (`x`, `y`) in PDFBox bottom-left
   points. Returns a byte[]. Defaults: \"Hello PDF\" at (72, 700)."
  (^bytes [] (simple-text-pdf "Hello PDF" 72.0 700.0))
  (^bytes [text x y]
   (with-open [doc (PDDocument.)]
     (let [page (PDPage. PDRectangle/LETTER)]
       (.addPage doc page)
       (with-open [cs (PDPageContentStream. doc page)]
         (.beginText cs)
         (.setFont cs (helvetica) 12.0)
         (.newLineAtOffset cs (float x) (float y))
         (.showText cs text)
         (.endText cs)))
     (->bytes doc))))

(defn multi-page-pdf
  "PDF with one page per string in `texts`, each drawn at (72, 700). Returns byte[]."
  ^bytes [texts]
  (with-open [doc (PDDocument.)]
    (doseq [t texts]
      (let [page (PDPage. PDRectangle/LETTER)]
        (.addPage doc page)
        (with-open [cs (PDPageContentStream. doc page)]
          (.beginText cs)
          (.setFont cs (helvetica) 12.0)
          (.newLineAtOffset cs (float 72.0) (float 700.0))
          (.showText cs ^String t)
          (.endText cs))))
    (->bytes doc)))

(defn pdf-with-metadata
  "Single-page PDF with the given document information set. `info` keys:
   :title :author :subject :keywords :creator. Returns byte[]."
  ^bytes [{:keys [title author subject keywords creator]}]
  (with-open [doc (PDDocument.)]
    (.addPage doc (PDPage. PDRectangle/LETTER))
    (let [pdi ^PDDocumentInformation (.getDocumentInformation doc)]
      (when title (.setTitle pdi title))
      (when author (.setAuthor pdi author))
      (when subject (.setSubject pdi subject))
      (when keywords (.setKeywords pdi keywords))
      (when creator (.setCreator pdi creator)))
    (->bytes doc)))

(defn encrypted-pdf
  "Single-page PDF encrypted with the given owner/user passwords. Returns byte[]."
  (^bytes [] (encrypted-pdf "owner" "user"))
  (^bytes [owner-pw user-pw]
   (with-open [doc (PDDocument.)]
     (.addPage doc (PDPage. PDRectangle/LETTER))
     (let [policy (StandardProtectionPolicy. owner-pw user-pw (AccessPermission.))]
       (.setEncryptionKeyLength policy 128)
       (.protect doc policy))
     (->bytes doc))))
