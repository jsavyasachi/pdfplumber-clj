(ns pdfplumber.signature
  "Digital-signature metadata and document-coverage inspection.

   This namespace does not perform cryptographic signature validation, certificate
   chain validation, revocation checking, or trust-anchor evaluation. The coverage
   flag is only an integrity signal showing whether a signature ByteRange spans the
   complete original PDF except for one signature-contents gap."
  (:import [java.lang ReflectiveOperationException]
           [java.lang.reflect Field]
           [java.util Calendar]
           [org.apache.pdfbox.io RandomAccessRead]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.interactive.digitalsignature PDSignature]))

(set! *warn-on-reflection* true)

(def ^:private pdf-source-field
  (delay
    (try
      (doto ^Field (.getDeclaredField PDDocument "pdfSource")
        (.setAccessible true))
      (catch ReflectiveOperationException _
        nil))))

(defn- source-length
  "Return the retained original PDF source length when PDFBox exposes it.

   PDFBox 3 retains the parsed RandomAccessRead as a private PDDocument field but
   has no public source-length accessor. Access is isolated here and failure yields
   nil, so callers omit the coverage signal instead of guessing from a reserialized
   document or trusting the ByteRange endpoint itself."
  [^PDDocument doc]
  (try
    (when-let [^Field field @pdf-source-field]
      (let [source (.get field doc)]
        (when (instance? RandomAccessRead source)
          (.length ^RandomAccessRead source))))
    (catch ReflectiveOperationException _
      nil)
    (catch RuntimeException _
      nil)))

(defn- whole-document-range?
  [byte-range ^long length]
  (and (= 4 (count byte-range))
       (let [[first-offset first-length second-offset second-length]
             (mapv long byte-range)]
         (and (zero? first-offset)
              (not (neg? first-length))
              (> second-offset (+ first-offset first-length))
              (not (neg? second-length))
              (= length (+ second-offset second-length))))))

(defn- signature-map
  [^PDSignature signature length]
  (let [^Calendar sign-date (.getSignDate signature)
        ^ints raw-byte-range (.getByteRange signature)
        byte-range (when (and raw-byte-range (pos? (alength raw-byte-range)))
                     (vec raw-byte-range))]
    (cond-> {}
      (some? (.getName signature))
      (assoc :name (.getName signature))

      (some? (.getReason signature))
      (assoc :reason (.getReason signature))

      (some? (.getLocation signature))
      (assoc :location (.getLocation signature))

      (some? (.getContactInfo signature))
      (assoc :contact-info (.getContactInfo signature))

      sign-date
      (assoc :signing-time (.toInstant sign-date))

      (some? (.getSubFilter signature))
      (assoc :sub-filter (.getSubFilter signature))

      (some? (.getFilter signature))
      (assoc :filter (.getFilter signature))

      byte-range
      (assoc :byte-range byte-range)

      (some? length)
      (assoc :covers-whole-document?
             (boolean (and byte-range
                           (whole-document-range? byte-range (long length))))))))

(defn signatures
  "Return a vector of signature metadata maps from `doc`.

   Present PDF fields are returned as `:name`, `:reason`, `:location`,
   `:contact-info`, `:signing-time`, `:sub-filter`, `:filter`, and `:byte-range`.
   `:covers-whole-document?` compares the ByteRange with the retained original
   source length and requires exactly one gap. It is omitted if that length cannot
   be obtained. This is an integrity signal only: cryptographic signature validity,
   certificate trust, and revocation status are not checked."
  [^PDDocument doc]
  (let [length (source-length doc)]
    (mapv #(signature-map % length) (.getSignatureDictionaries doc))))

(defn signed?
  "Return true when `doc` contains at least one signature dictionary.

   This reports presence only. It does not validate cryptographic signatures,
   certificates, revocation status, or trust."
  [doc]
  (boolean (seq (signatures doc))))
