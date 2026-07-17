(ns pdfplumber.fixtures
  "Deterministic fixture-PDF generators for tests (PDFBox writer side).
   Generating fixtures in code avoids opaque committed binaries and lets tests
   assert against known positions. Generated with PDFBox 3.0.x."
  (:import [org.apache.pdfbox.pdmodel PDDocument PDDocumentInformation PDPage PDPageContentStream]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.font PDType1Font Standard14Fonts$FontName]
           [org.apache.pdfbox.pdmodel.graphics.image LosslessFactory]
           [org.apache.pdfbox.pdmodel.encryption AccessPermission StandardProtectionPolicy]
           [java.awt Color]
           [java.awt.image BufferedImage]
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

(defn advanced-text-pdf
  "Single page with punctuation and two widely separated columns."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (doseq [[s x y] [["right" 300 700]
                         ["alpha,beta" 72 700]
                         ["second line" 72 670]]]
          (.beginText cs)
          (.setFont cs (helvetica) (float 12))
          (.newLineAtOffset cs (float x) (float y))
          (.showText cs ^String s)
          (.endText cs))))
    (->bytes doc)))

(defn ruled-pdf
  "Single US-Letter page with a horizontal rule (y=700, x 72..540), a vertical
   rule (x=72, y 500..700), and a stroked rectangle (x=100 y=400 w=200 h=100),
   all in PDFBox bottom-left points. Returns byte[]."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (.setLineWidth cs (float 1.0))
        (.moveTo cs (float 72) (float 700)) (.lineTo cs (float 540) (float 700)) (.stroke cs)
        (.moveTo cs (float 72) (float 500)) (.lineTo cs (float 72) (float 700)) (.stroke cs)
        (.addRect cs (float 100) (float 400) (float 200) (float 100)) (.stroke cs)))
    (->bytes doc)))

(defn curve-pdf
  "Single page with one painted cubic Bézier path."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (.moveTo cs (float 72) (float 600))
        (.curveTo cs (float 120) (float 650)
                  (float 180) (float 550)
                  (float 240) (float 600))
        (.stroke cs)))
    (->bytes doc)))

(defn table-pdf
  "Single US-Letter page with a 2x2 ruled table: vertical rules at x=72/300/540,
   horizontal rules at y=700/670/640, and the cells Date|Amount over
   2026-01-01|$10.00. Returns byte[]."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (.setLineWidth cs (float 1.0))
        (doseq [y [700 670 640]]
          (.moveTo cs (float 72) (float y)) (.lineTo cs (float 540) (float y)) (.stroke cs))
        (doseq [x [72 300 540]]
          (.moveTo cs (float x) (float 640)) (.lineTo cs (float x) (float 700)) (.stroke cs))
        (let [font (helvetica)]
          (doseq [[s x y] [["Date" 80 678] ["Amount" 308 678]
                           ["2026-01-01" 80 648] ["$10.00" 308 648]]]
            (.beginText cs)
            (.setFont cs font (float 10))
            (.newLineAtOffset cs (float x) (float y))
            (.showText cs ^String s)
            (.endText cs)))))
    (->bytes doc)))

(defn text-table-pdf
  "Single US-Letter page with a 2-column, 3-row table aligned by text position
   only (no ruling lines): left column at x=80, right at x=308; rows at
   y=700/670/640. Returns byte[]."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (let [font (helvetica)]
          (doseq [[s x y] [["Date" 80 700] ["Amount" 308 700]
                           ["2026-01-01" 80 670] ["$10.00" 308 670]
                           ["2026-02-01" 80 640] ["$20.00" 308 640]]]
            (.beginText cs)
            (.setFont cs font (float 10))
            (.newLineAtOffset cs (float x) (float y))
            (.showText cs ^String s)
            (.endText cs)))))
    (->bytes doc)))

(defn two-tables-pdf
  "Single page with two spatially separate ruled 2x2 tables. Returns byte[]."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (.setLineWidth cs (float 1.0))
        (doseq [{:keys [xs ys labels]}
                [{:xs [60 150 240] :ys [720 690 660]
                  :labels [["A" 68 698] ["B" 158 698]
                           ["1" 68 668] ["2" 158 668]]}
                 {:xs [330 420 510] :ys [520 490 460]
                  :labels [["C" 338 498] ["D" 428 498]
                           ["3" 338 468] ["4" 428 468]]}]]
          (doseq [y ys]
            (.moveTo cs (float (first xs)) (float y))
            (.lineTo cs (float (last xs)) (float y))
            (.stroke cs))
          (doseq [x xs]
            (.moveTo cs (float x) (float (last ys)))
            (.lineTo cs (float x) (float (first ys)))
            (.stroke cs))
          (doseq [[s x y] labels]
            (.beginText cs)
            (.setFont cs (helvetica) (float 10))
            (.newLineAtOffset cs (float x) (float y))
            (.showText cs ^String s)
            (.endText cs)))))
    (->bytes doc)))

(defn explicit-table-pdf
  "Single page with table text but no ruling lines. Returns byte[]."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (doseq [[s x y] [["Left" 80 700] ["Right" 180 700]
                         ["x" 80 670] ["y" 180 670]]]
          (.beginText cs)
          (.setFont cs (helvetica) (float 10))
          (.newLineAtOffset cs (float x) (float y))
          (.showText cs ^String s)
          (.endText cs)))
      (->bytes doc))))

(defn partially-ruled-table-pdf
  "Three text-aligned rows with horizontal rules but no vertical rules."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (doseq [y [710 680 650 620]]
          (.moveTo cs (float 70) (float y))
          (.lineTo cs (float 400) (float y))
          (.stroke cs))
        (doseq [[s x y] [["Date" 80 700] ["Amount" 308 700]
                         ["2026-01-01" 80 670] ["$10.00" 308 670]
                         ["2026-02-01" 80 640] ["$20.00" 308 640]]]
          (.beginText cs)
          (.setFont cs (helvetica) (float 10))
          (.newLineAtOffset cs (float x) (float y))
          (.showText cs ^String s)
          (.endText cs)))
      (->bytes doc))))

(defn image-pdf
  "Single page with a 2x3 RGB raster drawn at x=100, y=600, w=40, h=30."
  ^bytes []
  (with-open [doc (PDDocument.)]
    (let [page (PDPage. PDRectangle/LETTER)
          raster (BufferedImage. 2 3 BufferedImage/TYPE_INT_RGB)
          graphics (.createGraphics raster)]
      (try
        (.setColor graphics Color/RED)
        (.fillRect graphics 0 0 1 3)
        (.setColor graphics Color/BLUE)
        (.fillRect graphics 1 0 1 3)
        (finally (.dispose graphics)))
      (.addPage doc page)
      (let [image (LosslessFactory/createFromImage doc raster)]
        (with-open [cs (PDPageContentStream. doc page)]
          (.drawImage cs image (float 100) (float 600) (float 40) (float 30))))
      (->bytes doc))))

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
