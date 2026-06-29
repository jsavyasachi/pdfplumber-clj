(ns pdfplumber.document
  "Document loading and the error model. The PDFBox parse boundary lives here;
   higher namespaces work with the returned PDDocument handle."
  (:import [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.io RandomAccessReadBuffer]
           [org.apache.pdfbox.pdmodel.encryption InvalidPasswordException]
           [java.io File InputStream IOException]))

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
