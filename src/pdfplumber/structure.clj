(ns pdfplumber.structure
  "Tagged-PDF logical structure extraction."
  (:import [org.apache.pdfbox.cos COSArray COSBoolean COSDictionary COSInteger
            COSName COSNumber COSObject COSString]
           [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure
            PDAttributeObject PDMarkedContentReference PDStructureElement
            PDStructureTreeRoot Revisions]))

(set! *warn-on-reflection* true)

(declare cos->clj)

(defn- dictionary->map [^COSDictionary dictionary]
  (into {}
        (keep (fn [^COSName k]
                (when-let [value (cos->clj (.getDictionaryObject dictionary k))]
                  [(.getName k) value])))
        (.keySet dictionary)))

(defn- cos->clj [value]
  (cond
    (nil? value) nil
    (instance? COSObject value) (cos->clj (.getObject ^COSObject value))
    (instance? COSString value) (.getString ^COSString value)
    (instance? COSName value) (.getName ^COSName value)
    (instance? COSBoolean value) (.getValue ^COSBoolean value)
    (instance? COSInteger value) (.intValue ^COSInteger value)
    (instance? COSNumber value) (double (.floatValue ^COSNumber value))
    (instance? COSArray value)
    (let [array ^COSArray value]
      (mapv #(cos->clj (.getObject array (int %))) (range (.size array))))
    (instance? COSDictionary value) (dictionary->map ^COSDictionary value)
    :else nil))

(defn- attributes-map [^PDStructureElement element]
  (let [attributes ^Revisions (.getAttributes element)]
    (when (pos? (.size attributes))
      (let [merged
            (reduce (fn [result i]
                      (let [attribute ^PDAttributeObject (.getObject attributes (int i))]
                        (merge result (dictionary->map (.getCOSObject attribute)))))
                    {}
                    (range (.size attributes)))]
        (when (seq merged) merged)))))

(defn- page-numbers [^PDDocument doc]
  (into {}
        (map (fn [i]
               (let [page ^PDPage (.getPage doc (int i))]
                 [(.getCOSObject page) (inc i)])))
        (range (.getNumberOfPages doc))))

(defn- element-page-number [page-index ^PDPage page]
  (when page (get page-index (.getCOSObject page))))

(declare raw-element)

(defn- child-parts [page-index inherited-page kids]
  (reduce
   (fn [{:keys [mcids children] :as result} kid]
     (cond
       (integer? kid)
       (update result :mcids conj [inherited-page (int kid)])

       (instance? PDMarkedContentReference kid)
       (let [reference ^PDMarkedContentReference kid
             reference-page (or (element-page-number page-index (.getPage reference))
                                inherited-page)]
         (update result :mcids conj [reference-page (.getMCID reference)]))

       (instance? PDStructureElement kid)
       (update result :children conj
               (raw-element page-index inherited-page ^PDStructureElement kid))

       :else result))
   {:mcids [] :children []}
   kids))

(defn- raw-element [page-index inherited-page ^PDStructureElement element]
  (let [own-page (element-page-number page-index (.getPage element))
        effective-page (or own-page inherited-page)
        {:keys [mcids children]} (child-parts page-index effective-page (.getKids element))]
    (cond-> {:type (.getStructureType element)
             :mcids mcids
             :children children}
      (.getLanguage element) (assoc :lang (.getLanguage element))
      (.getAlternateDescription element)
      (assoc :alt-text (.getAlternateDescription element))
      (.getActualText element) (assoc :actual-text (.getActualText element))
      own-page (assoc :page-number own-page)
      (attributes-map element) (assoc :attributes (attributes-map element)))))

(defn- public-element [element]
  (-> element
      (update :mcids #(mapv second %))
      (update :children #(mapv public-element %))))

(defn- raw-tree [^PDDocument doc]
  (when-let [root ^PDStructureTreeRoot
             (some-> doc .getDocumentCatalog .getStructureTreeRoot)]
    (let [page-index (page-numbers doc)]
      (->> (.getKids root)
           (keep #(when (instance? PDStructureElement %)
                    (raw-element page-index nil ^PDStructureElement %)))
           vec))))

(defn structure-tree
  "Document logical structure as nested element maps. Uses kebab-case
   `:alt-text`, `:actual-text`, and `:page-number` keys."
  [^PDDocument doc]
  (mapv public-element (or (raw-tree doc) [])))

(defn- prune-to-page [page-number element]
  (let [mcids (filterv #(= page-number (first %)) (:mcids element))
        children (into [] (keep #(prune-to-page page-number %)) (:children element))]
    (when (or (= page-number (:page-number element)) (seq mcids) (seq children))
      (assoc element :mcids mcids :children children))))

(defn page-structure-tree
  "Logical structure associated with 1-based page `n`."
  [^PDDocument doc n]
  (->> (or (raw-tree doc) [])
       (keep #(prune-to-page n %))
       (mapv public-element)))
