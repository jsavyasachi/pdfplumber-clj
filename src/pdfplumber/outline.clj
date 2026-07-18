(ns pdfplumber.outline
  "Document outline extraction."
  (:import [java.io IOException]
           [org.apache.pdfbox.pdmodel PDDocument PDDocumentCatalog PDPage PDPageTree]
           [org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline
            PDDocumentOutline PDOutlineItem PDOutlineNode]))

(set! *warn-on-reflection* true)

(defn- destination-page-number [^PDDocument doc ^PDOutlineItem item]
  (try
    (when-let [page ^PDPage (.findDestinationPage item doc)]
      (let [pages ^PDPageTree (.getPages doc)
            index (.indexOf pages page)]
        (when-not (neg? index) (inc index))))
    (catch IOException _ nil)))

(declare outline-items)

(defn- outline-item [^PDDocument doc ^PDOutlineItem item]
  (let [page-number (destination-page-number doc item)]
    (cond-> {:title (.getTitle item)
             :children (outline-items doc item)}
      page-number (assoc :page-number page-number))))

(defn- outline-items [^PDDocument doc ^PDOutlineNode node]
  (loop [item (.getFirstChild node)
         result []]
    (if item
      (recur (.getNextSibling ^PDOutlineItem item)
             (conj result (outline-item doc item)))
      result)))

(defn outline
  "Return the nested document outline."
  [^PDDocument doc]
  (let [catalog ^PDDocumentCatalog (.getDocumentCatalog doc)
        root ^PDDocumentOutline (.getDocumentOutline catalog)]
    (if root (outline-items doc root) [])))
