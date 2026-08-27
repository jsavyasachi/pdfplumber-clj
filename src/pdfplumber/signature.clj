(ns pdfplumber.signature
  "Inspect and cryptographically verify PDF digital signatures.

   CMS signatures are verified with Bouncy Castle. Trust status is deliberately
   conservative: this namespace does not perform revocation checking or anchor
   a chain in a caller-provided trusted root, so a cryptographically valid chain
   is reported as `:untrusted`, not `:trusted`."
  (:import [java.io ByteArrayOutputStream]
           [java.lang ReflectiveOperationException]
           [java.lang.reflect Field]
           [java.security Provider Security]
           [java.util Calendar]
           [org.bouncycastle.cert X509CertificateHolder]
           [org.bouncycastle.cms CMSSignedData CMSProcessableByteArray
            SignerInformation SignerInformationVerifier]
           [org.bouncycastle.cms.jcajce JcaSimpleSignerInfoVerifierBuilder]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.apache.pdfbox.io RandomAccessRead]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.interactive.digitalsignature PDSignature]))

(set! *warn-on-reflection* true)

(def ^Provider bc-provider
  (doto (BouncyCastleProvider.)
    (Security/addProvider)))

(def ^:private pdf-source-field
  (delay
    (try
      (doto ^Field (.getDeclaredField PDDocument "pdfSource")
        (.setAccessible true))
      (catch ReflectiveOperationException _
        nil))))

(defn- source-bytes
  "Return the retained original PDF source if PDFBox exposes it.

   PDFBox 3 retains the parsed RandomAccessRead as a private PDDocument field.
   It has no public source-length accessor. This function isolates access. On
   failure, it returns nil."
  [^PDDocument doc]
  (try
    (when-let [^Field field @pdf-source-field]
      (let [^RandomAccessRead source (.get field doc)]
        (when (instance? RandomAccessRead source)
          (let [length (.length source)
                result (ByteArrayOutputStream. (int length))
                buffer (byte-array 8192)]
            (.seek source 0)
            (loop []
              (let [n (.read source buffer)]
                (when (pos? n)
                  (.write result buffer 0 n)
                  (recur))))
            (.seek source 0)
            (.toByteArray result)))))
    (catch ReflectiveOperationException _
      nil)
    (catch RuntimeException _
      nil)))

(defn- certificate-map [^X509CertificateHolder certificate]
  {:subject (str (.getSubject certificate))
   :issuer (str (.getIssuer certificate))
   :serial-number (str (.getSerialNumber certificate))
   :not-before (.toInstant (.getNotBefore certificate))
   :not-after (.toInstant (.getNotAfter certificate))})

(defn- verify-cms [^PDSignature signature ^bytes source]
  (try
    (let [signed-content (.getSignedContent signature source)
          cms (CMSSignedData. (CMSProcessableByteArray. signed-content)
                              (.getContents signature))
          certificates (.getCertificates cms)
          ^SignerInformation signer (first (.getSigners (.getSignerInfos cms)))
          ^X509CertificateHolder signer-cert
          (first (when signer (.getMatches certificates (.getSID signer))))
          matches (.getMatches certificates nil)
          ^JcaSimpleSignerInfoVerifierBuilder verifier-builder
          (JcaSimpleSignerInfoVerifierBuilder.)
          ^SignerInformationVerifier verifier
          (do
            (.setProvider ^JcaSimpleSignerInfoVerifierBuilder verifier-builder bc-provider)
            (.build ^JcaSimpleSignerInfoVerifierBuilder verifier-builder signer-cert))
          digest-valid? (boolean (and signer-cert
                                      (.verify signer verifier)))]
      {:signer-identity (some-> signer-cert .getSubject str)
       :certificate-chain (mapv certificate-map matches)
       :digest-valid? digest-valid?
       :chain-valid? (boolean (and signer-cert
                                    (every? true?
                                            (map (fn [pair]
                                                   (let [^X509CertificateHolder child (first pair)
                                                         ^X509CertificateHolder issuer (second pair)]
                                                     (= (.getIssuer child)
                                                        (.getSubject issuer))))
                                                 (partition 2 1 matches)))))
       :trust-status (if digest-valid? :untrusted :invalid)
       :revocation-checked? false})
    (catch Exception _
      {:signer-identity nil
       :certificate-chain []
       :digest-valid? false
       :chain-valid? false
       :trust-status :invalid
       :revocation-checked? false})))

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
  [^PDSignature signature source]
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

      (some? source)
      (assoc :covers-whole-document?
             (boolean (and byte-range
                           (whole-document-range? byte-range (alength ^bytes source)))))

      (some? source)
      (merge (verify-cms signature source))

      (nil? source)
      (merge {:signer-identity nil
              :certificate-chain []
              :digest-valid? nil
              :chain-valid? nil
              :trust-status :unknown
              :revocation-checked? false}))))

(defn signatures
  "Return a vector of signature metadata maps from `doc`.

   Present PDF fields are returned as `:name`, `:reason`, `:location`,
   `:contact-info`, `:signing-time`, `:sub-filter`, `:filter`, and `:byte-range`.
   `:covers-whole-document?` compares the ByteRange with the original source
   length and requires exactly one gap. The function omits it if it cannot get
   the length.
   In addition to the metadata fields, each result includes `:digest-valid?`,
   `:signer-identity`, `:certificate-chain`, `:chain-valid?`,
   `:trust-status`, and `:revocation-checked?`. `:covers-whole-document?` is a
   separate, prominent byte-range check: a true digest does not make a partial
   incremental revision safe. Trust is `:untrusted` for valid CMS signatures
   because no trusted root or revocation service is configured; `:invalid`
   means CMS verification failed."
  [^PDDocument doc]
  (let [source (source-bytes doc)]
    (mapv #(signature-map % source) (.getSignatureDictionaries doc))))

(def verify-signatures
  "Alias for `signatures`, which returns cryptographically verified results."
  signatures)

(defn signed?
  "Return true when `doc` contains at least one signature dictionary.

   This reports presence only. It does not validate cryptographic signatures,
   certificates, revocation status, or trust."
  [doc]
  (boolean (seq (signatures doc))))
