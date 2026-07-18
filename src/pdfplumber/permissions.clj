(ns pdfplumber.permissions
  "Document access permissions."
  (:import [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.encryption AccessPermission PDEncryption]))

(set! *warn-on-reflection* true)

(defn permissions
  "Return encryption and access-permission flags."
  [^PDDocument doc]
  (let [encrypted? (.isEncrypted doc)
        access ^AccessPermission (.getCurrentAccessPermission doc)
        encryption ^PDEncryption (when encrypted? (.getEncryption doc))
        handler (when encryption (.getFilter encryption))]
    (cond-> {:encrypted? encrypted?
             :can-print? (.canPrint access)
             :can-modify? (.canModify access)
             :can-extract-content? (.canExtractContent access)
             :can-extract-for-accessibility? (.canExtractForAccessibility access)
             :can-assemble-document? (.canAssembleDocument access)
             :can-fill-in-form? (.canFillInForm access)
             :can-modify-annotations? (.canModifyAnnotations access)
             :can-print-faithful? (.canPrintFaithful access)}
      encryption (assoc :key-length (.getLength encryption))
      handler (assoc :security-handler handler))))
