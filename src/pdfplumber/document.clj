(ns pdfplumber.document
  "Load documents and handle errors. PDFBox parses documents here. Higher
   namespaces use the returned PDDocument handle."
  (:import [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument PDDocumentCatalog PDDocumentInformation PDPage]
           [org.apache.pdfbox.pdmodel.common PDRectangle]
           [org.apache.pdfbox.pdmodel.common PDMetadata]
           [org.apache.pdfbox.pdmodel.interactive.viewerpreferences PDViewerPreferences]
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
  "Open a PDF and return a PDFBox `PDDocument` handle. It accepts a path `String`,
   `java.io.File`, `byte[]`, or `java.io.InputStream` (an already-open
   `PDDocument` is returned as-is). An optional `{:password string}` map supplies
   the password for an encrypted PDF. The function ignores the password when
   `source` is an open `PDDocument`. The caller closes the result. Use
   `pdfplumber.core/with-pdf`.

   Throws `ex-info` carrying `:pdfplumber/error`:
   `:invalid-input` (unsupported source or missing file),
   `:encrypted-pdf` (password-protected when no password is supplied or the
   supplied password is incorrect),
   `:parse-failed` (not a readable PDF)."
  (^PDDocument [source] (open-pdf source nil))
  (^PDDocument [source opts]
   (let [password (:password opts)]
     (try
       (cond
         (instance? PDDocument source) source

         (string? source)
         (let [f (File. ^String source)]
           (if (.exists f)
             (if (nil? password)
               (Loader/loadPDF f)
               (Loader/loadPDF f ^String password))
             (fail! :invalid-input (str "File not found: " source) {:path source})))

         (instance? File source)
         (if (nil? password)
           (Loader/loadPDF ^File source)
           (Loader/loadPDF ^File source ^String password))

         (bytes? source)
         (if (nil? password)
           (Loader/loadPDF ^bytes source)
           (Loader/loadPDF ^bytes source ^String password))

         (instance? InputStream source)
         (let [random-access (RandomAccessReadBuffer. ^InputStream source)]
           (if (nil? password)
             (Loader/loadPDF random-access)
             (Loader/loadPDF random-access ^String password)))

         :else
         (fail! :invalid-input (str "Unsupported PDF source: " (class source))
                {:source-class (.getName (class source))}))
       (catch InvalidPasswordException e
         (fail! :encrypted-pdf "PDF is encrypted or password-protected" {} e))
       (catch IOException e
         (fail! :parse-failed "Failed to parse PDF"
                {:cause-class (.getName (class e))
                 :cause-message (.getMessage e)}
                e))))))

(defn- cal->iso [^Calendar c]
  (when c (str (.toInstant c))))

(defn- xmp-bytes [^PDMetadata metadata]
  (when metadata
    (with-open [^InputStream in (.exportXMPMetadata metadata)]
      (.readAllBytes in))))

(defn- viewer-preferences [^PDDocumentCatalog catalog]
  (when-let [^PDViewerPreferences prefs (.getViewerPreferences catalog)]
    {:display-doc-title? (.displayDocTitle prefs)
     :center-window? (.centerWindow prefs)
     :hide-toolbar? (.hideToolbar prefs)
     :hide-menubar? (.hideMenubar prefs)
     :hide-window-ui? (.hideWindowUI prefs)
     :fit-window? (.fitWindow prefs)
     :reading-direction (.getReadingDirection prefs)
     :non-full-screen-page-mode (.getNonFullScreenPageMode prefs)
     :view-area (.getViewArea prefs)
     :view-clip (.getViewClip prefs)
     :print-area (.getPrintArea prefs)
     :print-clip (.getPrintClip prefs)
     :duplex (.getDuplex prefs)
     :print-scaling (.getPrintScaling prefs)}))

(defn metadata
  "Document metadata as a map. Always includes `:page-count`; document-info
   fields (`:title` `:author` `:subject` `:keywords` `:creator` `:producer`
   `:creation-date` `:modification-date`) are included only when present. Dates
   use ISO-8601 strings."
  [^PDDocument doc]
  (let [info ^PDDocumentInformation (.getDocumentInformation doc)
        catalog (.getDocumentCatalog doc)
        labels (try (some-> (.getPageLabels catalog) .getLabelsByPageIndices vec)
                    (catch IOException _ nil))]
    (into {:page-count (.getNumberOfPages doc)}
          (remove (comp nil? val))
          {:title (.getTitle info)
           :author (.getAuthor info)
           :subject (.getSubject info)
           :keywords (.getKeywords info)
           :creator (.getCreator info)
           :producer (.getProducer info)
           :creation-date (cal->iso (.getCreationDate info))
           :modification-date (cal->iso (.getModificationDate info))
           :page-labels labels
           :language (.getLanguage catalog)
           :viewer-preferences (viewer-preferences catalog)
           :xmp-metadata (xmp-bytes (.getMetadata catalog))})))

(defn- normalize-box [^PDRectangle box rotation]
  (let [x0 (double (min (.getLowerLeftX box) (.getUpperRightX box)))
        y0 (double (min (.getLowerLeftY box) (.getUpperRightY box)))
        x1 (double (max (.getLowerLeftX box) (.getUpperRightX box)))
        y1 (double (max (.getLowerLeftY box) (.getUpperRightY box)))]
    (if (contains? #{90 270} rotation) [y0 x0 y1 x1] [x0 y0 x1 y1])))

(defn- invert-box [[x0 y0 x1 y1] media-height]
  [x0 (- media-height y1) x1 (- media-height y0)])

(defn- page-map [^PDPage page ^long n page-label]
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
     :page-label page-label
     :mediabox mediabox
     :cropbox cropbox
     :bleedbox (invert-box (normalize-box (.getBleedBox page) rotation) media-height)
     :trimbox (invert-box (normalize-box (.getTrimBox page) rotation) media-height)
     :artbox (invert-box (normalize-box (.getArtBox page) rotation) media-height)
     :bbox mediabox}))

(defn pages
  "Vector of page maps with media, crop, and active bounding boxes, dimensions,
   rotation, and 1-based page numbers."
  [^PDDocument doc]
  (let [labels (try (some-> (.getPageLabels (.getDocumentCatalog doc))
                                      .getLabelsByPageIndices)
                    (catch IOException _ nil))]
    (mapv #(page-map (.getPage doc %) (inc %) (when labels (nth labels %)))
          (range (.getNumberOfPages doc)))))

(defn page
  "The page map for 1-based page number `n`. Throws `ex-info`
   `:pdfplumber/error :page-not-found` (with `:page` and `:page-count`) when out
   of range."
  [^PDDocument doc n]
  (let [pc (.getNumberOfPages doc)]
    (if (and (integer? n) (<= 1 n pc))
        (let [labels (try (some-> (.getPageLabels (.getDocumentCatalog doc))
                                            .getLabelsByPageIndices)
                        (catch IOException _ nil))]
        (page-map (.getPage doc (dec n)) n (when labels (nth labels (dec n)))))
      (fail! :page-not-found (str "No page " n " (document has " pc ")")
             {:page n :page-count pc}))))
