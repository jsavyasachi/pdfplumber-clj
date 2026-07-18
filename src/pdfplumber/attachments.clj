(ns pdfplumber.attachments
  "Embedded-file extraction."
  (:import [java.util Calendar]
           [org.apache.pdfbox.pdmodel
            PDDocument PDDocumentCatalog PDDocumentNameDictionary PDEmbeddedFilesNameTreeNode]
           [org.apache.pdfbox.pdmodel.common PDNameTreeNode]
           [org.apache.pdfbox.pdmodel.common.filespecification
            PDComplexFileSpecification PDEmbeddedFile]))

(set! *warn-on-reflection* true)

(defn- name-tree-entries [^PDNameTreeNode node]
  (let [names (.getNames node)
        kids (.getKids node)]
    (into (if names (vec names) [])
          (mapcat name-tree-entries)
          (or kids []))))

(defn- instant [^Calendar value]
  (when value (.toInstant value)))

(defn- attachment-map [name ^PDComplexFileSpecification spec include-data?]
  (when-let [embedded ^PDEmbeddedFile
             (or (.getEmbeddedFileUnicode spec) (.getEmbeddedFile spec))]
    (let [description (.getFileDescription spec)
          size (.getSize embedded)
          mime-type (.getSubtype embedded)
          creation-date (instant (.getCreationDate embedded))
          mod-date (instant (.getModDate embedded))]
      (cond-> {:name name}
        description (assoc :description description)
        (not (neg? size)) (assoc :size size)
        mime-type (assoc :mime-type mime-type)
        creation-date (assoc :creation-date creation-date)
        mod-date (assoc :mod-date mod-date)
        include-data? (assoc :bytes (.toByteArray embedded))))))

(defn attachments
  "Return document attachments and optional decoded data."
  ([doc] (attachments doc {}))
  ([^PDDocument doc opts]
   (let [catalog ^PDDocumentCatalog (.getDocumentCatalog doc)
         names ^PDDocumentNameDictionary (.getNames catalog)
         tree ^PDEmbeddedFilesNameTreeNode
         (when names (.getEmbeddedFiles names))]
     (if tree
       (into []
             (keep (fn [[name spec]]
                     (attachment-map name spec (:include-data? opts))))
             (name-tree-entries tree))
       []))))
