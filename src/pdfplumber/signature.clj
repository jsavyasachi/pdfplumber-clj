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
           [java.util Calendar Date Locale]
           [org.bouncycastle.cert X509CertificateHolder]
           [org.bouncycastle.cert.jcajce JcaX509CertificateConverter]
           [org.bouncycastle.util.encoders Hex]
           [org.bouncycastle.cms CMSSignedData CMSProcessableByteArray
            SignerInformation]
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

(def ^:private max-certificate-chain-length 100)

(defn- ordered-certificate-chain
  [^X509CertificateHolder signer-cert certificates]
  (loop [current signer-cert
         chain []
         visited #{}]
    (cond
      (nil? current) chain
      (contains? visited current) nil
      (>= (count chain) max-certificate-chain-length) nil
      :else
      (let [chain (conj chain current)
            visited (conj visited current)
            issuer (first (filter #(= (.getIssuer current) (.getSubject ^X509CertificateHolder %))
                                  certificates))]
        (if (= (.getSubject current) (.getIssuer current))
          chain
          (recur issuer chain visited))))))

(defn- valid-certificate-chain?
  [chain]
  (when (seq chain)
    (let [converter (doto (JcaX509CertificateConverter.)
                      (.setProvider bc-provider))
          certificates (mapv #(.getCertificate converter %) chain)
          ca? (fn [certificate]
                (and (<= 0 (.getBasicConstraints certificate))
                     (or (nil? (.getKeyUsage certificate))
                         (true? (aget (.getKeyUsage certificate) 5)))))]
      (try
        (every? true?
                (concat
                (map (fn [certificate]
                       (try (.checkValidity certificate (Date.)) true
                            (catch Exception _ false)))
                     certificates)
                 (map (fn [[child issuer]]
                        (and (ca? issuer)
                             (do (.verify child (.getPublicKey issuer) bc-provider) true)))
                      (partition 2 1 (conj (vec certificates) (last certificates))))))
        (catch Exception _ false)))))

(defn- verify-signer
  [^SignerInformation signer certificates
   ^JcaSimpleSignerInfoVerifierBuilder verifier-builder]
  (let [^X509CertificateHolder certificate
        (first (.getMatches certificates (.getSID signer)))]
    (try
      {:signer-identity (some-> certificate .getSubject str)
       :digest-valid? (boolean (and certificate
                                     (.verify signer
                                              (.build verifier-builder certificate))))}
      (catch Exception _
        {:signer-identity (some-> certificate .getSubject str)
         :digest-valid? false}))))

(defn- verify-cms [^PDSignature signature ^bytes source]
  (try
    (let [signed-content (.getSignedContent signature source)
          cms (CMSSignedData. (CMSProcessableByteArray. signed-content)
                              (.getContents signature))
          certificates (.getCertificates cms)
          signers (vec (.getSigners (.getSignerInfos cms)))
          ^SignerInformation signer (first signers)
          ^X509CertificateHolder signer-cert
          (first (when signer (.getMatches certificates (.getSID signer))))
          matches (.getMatches certificates nil)
          ordered-chain (when signer-cert
                          (ordered-certificate-chain signer-cert matches))
          ^JcaSimpleSignerInfoVerifierBuilder verifier-builder
          (doto (JcaSimpleSignerInfoVerifierBuilder.)
            (.setProvider bc-provider))
          signer-results (mapv #(verify-signer % certificates verifier-builder) signers)
          digest-valid? (boolean (and (seq signer-results)
                                      (every? :digest-valid? signer-results)))]
      {:signer-identity (some-> signer-cert .getSubject str)
       :certificate-chain (mapv certificate-map (or ordered-chain []))
       :signers signer-results
       :digest-valid? digest-valid?
       :chain-valid? (boolean (valid-certificate-chain? ordered-chain))
       :trust-status (if digest-valid? :untrusted :invalid)
       :revocation-checked? false})
    (catch Exception _
      {:signer-identity nil
       :certificate-chain []
       :signers []
       :digest-valid? false
       :chain-valid? false
       :trust-status :invalid
       :revocation-checked? false})))

(defn- contents-gap?
  [^PDSignature signature ^bytes source ^long start ^long end]
  (let [serialized (str "<" (Hex/toHexString (.getContents signature)) ">")
        gap (String. source (int start) (int (- end start)) "ISO-8859-1")]
    (and (= (count serialized) (- end start))
         (= serialized (.toLowerCase gap Locale/ROOT)))))

(defn- whole-document-range?
  [^PDSignature signature ^bytes source byte-range]
  (and (= 4 (count byte-range))
       (let [[first-offset first-length second-offset second-length]
             (mapv long byte-range)]
         (and (zero? first-offset)
              (not (neg? first-length))
              (> second-offset (+ first-offset first-length))
              (not (neg? second-length))
              (= (alength source) (+ second-offset second-length))
              (contents-gap? signature source (+ first-offset first-length) second-offset)))))

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
                           (whole-document-range? signature source byte-range))))

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
