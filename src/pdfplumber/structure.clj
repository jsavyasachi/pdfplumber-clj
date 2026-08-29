(ns pdfplumber.structure
  "Extract tagged-PDF logical structures."
  (:require [pdfplumber.text :as text])
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
   (fn [result kid]
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

(defn- element-elements [path element]
  (cons [path element]
        (mapcat (fn [[child-index child]]
                  (element-elements (conj path child-index) child))
                (map-indexed vector (:children element)))))

(defn- tree-elements [tree]
  (mapcat (fn [[index element]]
            (element-elements [index] element))
          (map-indexed vector tree)))

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

(defn character-associations
  "Associate extracted characters with direct structure-tree elements."
  ([^PDDocument doc] (character-associations doc {}))
  ([^PDDocument doc opts]
   (if-let [tree (raw-tree doc)]
     (let [index (into {}
                       (mapcat (fn [[path element]]
                                 (map (fn [[page mcid]]
                                        [[page mcid]
                                         {:path path
                                          :type (:type element)
                                          :mcid mcid}])
                                      (:mcids element)))
                               (tree-elements tree)))
           ;; MCID callbacks surround processTextPosition, but PDFTextStripper's
           ;; batched writeString callback runs after the marked-content scope
           ;; has ended. Text-flow mode therefore is required for associations.
           chars (text/chars doc (assoc opts :include-mcid? true
                                        :use-text-flow true))]
       (mapv (fn [char]
               (let [element (get index [(:page-number char) (:mcid char)])]
                 {:char (dissoc char :mcid)
                  :element element
                  :confidence (if element :exact :unmapped)}))
             chars))
     [])))

(defn text-spans
  "Extract text spans with direct structure-tree associations."
  ([^PDDocument doc] (text-spans doc {}))
  ([^PDDocument doc opts]
   (->> (character-associations doc opts)
        (partition-by #(select-keys % [:element :confidence]))
        (mapv (fn [associations]
                (let [first-association (first associations)]
                  {:text (apply str (map #(get-in % [:char :text]) associations))
                   :chars (mapv :char associations)
                   :element (:element first-association)
                   :confidence (:confidence first-association)}))))))
