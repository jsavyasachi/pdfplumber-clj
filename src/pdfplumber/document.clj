(ns pdfplumber.document
  "Document loading and the error model. The PDFBox parse boundary lives here;
   higher namespaces work with the returned PDDocument handle."
  (:import [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument PDDocumentInformation PDPage]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.io RandomAccessReadBuffer]
           [org.apache.pdfbox.pdmodel.encryption InvalidPasswordException]
           [java.io File InputStream IOException]
           [java.util Calendar]))

(set! *warn-on-reflection* true)

(defn- fail!
  ([error msg data] (fail! error msg data nil))
  ([error msg data cause]
   (throw (ex-info msg (assoc data :pdfplumber/error error) cause))))

(defn open-pdf
  "Open a PDF and return a PDFBox `PDDocument` handle. Accepts a path `String`,
   `java.io.File`, `byte[]`, or `java.io.InputStream` (an already-open
   `PDDocument` is returned as-is). The caller owns closing it; prefer
   `pdfplumber.core/with-pdf`.

   Throws `ex-info` carrying `:pdfplumber/error`:
   `:invalid-input` (unsupported source or missing file),
   `:encrypted-pdf` (password-protected),
   `:parse-failed` (not a readable PDF)."
  ^PDDocument [source]
  (try
    (cond
      (instance? PDDocument source) source

      (string? source)
      (let [f (File. ^String source)]
        (if (.exists f)
          (Loader/loadPDF f)
          (fail! :invalid-input (str "File not found: " source) {:path source})))

      (instance? File source) (Loader/loadPDF ^File source)

      (bytes? source) (Loader/loadPDF ^bytes source)

      (instance? InputStream source)
      (Loader/loadPDF (RandomAccessReadBuffer. ^InputStream source))

      :else
      (fail! :invalid-input (str "Unsupported PDF source: " (class source))
             {:source-class (.getName (class source))}))
    (catch InvalidPasswordException e
      (fail! :encrypted-pdf "PDF is encrypted or password-protected" {} e))
    (catch IOException e
      (fail! :parse-failed "Failed to parse PDF"
             {:cause-class (.getName (class e))
              :cause-message (.getMessage e)}
             e))))

(defn- cal->iso [^Calendar c]
  (when c (str (.toInstant c))))

(defn metadata
  "Document metadata as a map. Always includes `:page-count`; document-info
   fields (`:title` `:author` `:subject` `:keywords` `:creator` `:producer`
   `:creation-date` `:modification-date`) are included only when present. Dates
   are ISO-8601 strings."
  [^PDDocument doc]
  (let [info ^PDDocumentInformation (.getDocumentInformation doc)]
    (into {:page-count (.getNumberOfPages doc)}
          (remove (comp nil? val))
          {:title (.getTitle info)
           :author (.getAuthor info)
           :subject (.getSubject info)
           :keywords (.getKeywords info)
           :creator (.getCreator info)
           :producer (.getProducer info)
           :creation-date (cal->iso (.getCreationDate info))
           :modification-date (cal->iso (.getModificationDate info))})))

(defn- normalize-box [^PDRectangle box rotation]
  (let [x0 (double (min (.getLowerLeftX box) (.getUpperRightX box)))
        y0 (double (min (.getLowerLeftY box) (.getUpperRightY box)))
        x1 (double (max (.getLowerLeftX box) (.getUpperRightX box)))
        y1 (double (max (.getLowerLeftY box) (.getUpperRightY box)))]
    (if (contains? #{90 270} rotation) [y0 x0 y1 x1] [x0 y0 x1 y1])))

(defn- invert-box [[x0 y0 x1 y1] media-height]
  [x0 (- media-height y1) x1 (- media-height y0)])

(defn- page-map [^PDPage page ^long n]
  (let [rotation (mod (.getRotation page) 360)
        media-raw (normalize-box (.getMediaBox page) rotation)
        media-height (- (nth media-raw 3) (nth media-raw 1))
        mediabox (invert-box media-raw media-height)
        cropbox (invert-box (normalize-box (.getCropBox page) rotation) media-height)
        w (- (nth mediabox 2) (first mediabox))
        h (- (nth mediabox 3) (second mediabox))]
    {:page-number n
     :width w
     :height h
     :rotation rotation
     :mediabox mediabox
     :cropbox cropbox
     :bbox mediabox}))

(defn pages
  "Vector of page maps with media, crop, and active bounding boxes, dimensions,
   rotation, and 1-based page numbers."
  [^PDDocument doc]
  (mapv #(page-map (.getPage doc %) (inc %))
        (range (.getNumberOfPages doc))))

(defn page
  "The page map for 1-based page number `n`. Throws `ex-info`
   `:pdfplumber/error :page-not-found` (with `:page` and `:page-count`) when out
   of range."
  [^PDDocument doc n]
  (let [pc (.getNumberOfPages doc)]
    (if (and (integer? n) (<= 1 n pc))
      (page-map (.getPage doc (dec n)) n)
      (fail! :page-not-found (str "No page " n " (document has " pc ")")
             {:page n :page-count pc}))))
