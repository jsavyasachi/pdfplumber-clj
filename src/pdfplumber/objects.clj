(ns pdfplumber.objects
  "Page object extraction (lines, rectangles, curves, and images) via a
   PDFGraphicsStreamEngine subclass.

   PDFBox delivers path coordinates already transformed by the CTM into page
   space (bottom-left origin); we collect painted subpaths and flip them to the
   public top-left coordinate system. Only painted paths (stroked/filled) yield
   objects; clip-only / no-paint paths are discarded."
  (:require [pdfplumber.geometry :as g]
            [pdfplumber.page :as page]
            [clojure.string :as str])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.contentstream PDFGraphicsStreamEngine]
           [org.apache.pdfbox.cos COSName]
           [org.apache.pdfbox.pdmodel.graphics.image PDImage]
           [org.apache.pdfbox.pdmodel.graphics.color PDColor]
           [org.apache.pdfbox.pdmodel.graphics.state PDGraphicsState]
           [org.apache.pdfbox.pdmodel.interactive.annotation PDAnnotation PDAnnotationLink
            PDAnnotationMarkup PDAnnotationWidget]
           [org.apache.pdfbox.pdmodel.interactive.action PDActionURI]
           [org.apache.pdfbox.pdmodel.interactive.form PDAcroForm PDButton PDChoice
            PDField PDSignatureField PDTerminalField PDTextField]
           [org.apache.pdfbox.util Matrix]
           [java.awt.geom Point2D Point2D$Float]
           [java.io ByteArrayOutputStream]
           [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(def ^:private orient-tolerance 0.1)

(defn- pt [^Point2D p] [(.getX p) (.getY p)])

(defn- color-components [^PDColor color]
  (some->> color .getComponents (mapv double)))

(defn- paint-attrs [^PDFGraphicsStreamEngine engine]
  (let [^PDGraphicsState gs (.getGraphicsState engine)]
    {:linewidth (double (.getLineWidth gs))
     :stroking-color (color-components (.getStrokingColor gs))
     :non-stroking-color (color-components (.getNonStrokingColor gs))}))

(defn- rich-bbox [type page-h page-no doctop-offset x0 top x1 bottom attrs]
  (merge {:type type
          :object-type type
          :x0 x0 :top top :x1 x1 :bottom bottom
          :y0 (- page-h bottom) :y1 (- page-h top)
          :width (- x1 x0) :height (- bottom top)
          :doctop (+ doctop-offset top)
          :page-number page-no}
         attrs))

(defn- line-obj [page-h page-no doctop-offset attrs [x0 y0] [x1 y1]]
  (let [t0 (g/flip-y page-h y0)
        t1 (g/flip-y page-h y1)
        top (min t0 t1)
        bottom (max t0 t1)
        lo-x (min x0 x1)
        hi-x (max x0 x1)]
    (assoc (rich-bbox :line page-h page-no doctop-offset
                      lo-x top hi-x bottom attrs)
           :orientation (cond
                          (<= (- bottom top) orient-tolerance) :horizontal
                          (<= (- hi-x lo-x) orient-tolerance) :vertical
                          :else :other))))

(defn- rect-obj [page-h page-no doctop-offset attrs corners]
  (let [xs (map first corners)
        tops (map #(g/flip-y page-h (second %)) corners)
        x0 (apply min xs) top (apply min tops)
        x1 (apply max xs) bottom (apply max tops)]
    (rich-bbox :rect page-h page-no doctop-offset x0 top x1 bottom attrs)))

(defn- curve-obj [page-h page-no doctop-offset attrs points]
  (let [xs (map first points)
        tops (map #(g/flip-y page-h (second %)) points)
        pts (mapv (fn [[x y]] [(double x) (double (g/flip-y page-h y))]) points)
        x0 (apply min xs) top (apply min tops)
        x1 (apply max xs) bottom (apply max tops)]
    (assoc (rich-bbox :curve page-h page-no doctop-offset x0 top x1 bottom attrs)
           :pts pts)))

(defn- png-bytes ^bytes [^PDImage image]
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write (.getImage image) "png" out)
    (.toByteArray out)))

(defn- image-obj [page-h page-no ^PDImage image ^Matrix ctm include-data?]
  (let [corners (map (fn [[x y]] (pt (.transformPoint ctm (float x) (float y))))
                     [[0 0] [1 0] [0 1] [1 1]])
        xs (map first corners)
        tops (map #(g/flip-y page-h (second %)) corners)
        colorspace (some-> image .getColorSpace .getName)
        cos-image (.getCOSObject image)]
    (cond-> {:type :image
             :object-type :image
             :x0 (apply min xs) :top (apply min tops)
             :x1 (apply max xs) :bottom (apply max tops)
             :width (.getWidth image)
             :height (.getHeight image)
             :colorspace colorspace
             :bits (.getBitsPerComponent image)
             :srgb? (= "DeviceRGB" colorspace)
             :mask? (.isStencil image)
             :smask? (.containsKey cos-image COSName/SMASK)
             :page-number page-no}
      include-data? (assoc :bytes (png-bytes image)))))

(defn- object-engine
  "A PDFGraphicsStreamEngine that appends top-left object maps to `out`."
  ^PDFGraphicsStreamEngine [^PDPage page page-no doctop-offset out include-image-data?]
  (let [page-h (double (.getHeight (.getMediaBox page)))
        st (atom {:cur nil :start nil :lines [] :rects [] :curves []})
        engine-holder (atom nil)
        flush! (fn []
                 (let [{:keys [lines rects curves]} @st
                       attrs (paint-attrs ^PDFGraphicsStreamEngine @engine-holder)]
                   (doseq [[a b] lines] (swap! out conj (line-obj page-h page-no doctop-offset attrs a b)))
                   (doseq [r rects] (swap! out conj (rect-obj page-h page-no doctop-offset attrs r)))
                   (doseq [c curves] (swap! out conj (curve-obj page-h page-no doctop-offset attrs c)))
                   (swap! st assoc :lines [] :rects [] :curves [])))
        clear! (fn [] (swap! st assoc :lines [] :rects [] :curves []))
        engine
        (proxy [PDFGraphicsStreamEngine] [page]
          (appendRectangle [p0 p1 p2 p3]
            (swap! st update :rects conj (mapv pt [p0 p1 p2 p3])))
          (moveTo [x y]
            (swap! st assoc :cur [x y] :start [x y]))
          (lineTo [x y]
            (swap! st (fn [s] (-> s
                                  (update :lines conj [(:cur s) [x y]])
                                  (assoc :cur [x y])))))
          (curveTo [x1 y1 x2 y2 x3 y3]
            (swap! st (fn [s] (-> s
                                  (update :curves conj [(:cur s) [x1 y1] [x2 y2] [x3 y3]])
                                  (assoc :cur [x3 y3])))))
          (getCurrentPoint []
            (let [[x y] (or (:cur @st) [0.0 0.0])]
              (Point2D$Float. (float x) (float y))))
          (closePath []
            (swap! st (fn [s] (cond-> s
                                (and (:cur s) (:start s))
                                (update :lines conj [(:cur s) (:start s)])))))
          (endPath [] (clear!))
          (strokePath [] (flush!))
          (fillPath [_winding-rule] (flush!))
          (fillAndStrokePath [_winding-rule] (flush!))
          (drawImage [pd-image]
            (let [graphics-state (.getGraphicsState ^PDFGraphicsStreamEngine @engine-holder)]
              (swap! out conj
                     (image-obj page-h page-no pd-image
                                (.getCurrentTransformationMatrix graphics-state)
                                include-image-data?))))
          (clip [_winding-rule])
          (shadingFill [_shading-name]))]
    (clojure.core/reset! engine-holder engine)
    engine))

(defn- page-height [^PDDocument doc ^long p]
  (double (.getHeight (.getMediaBox (.getPage doc (dec (int p)))))))

(defn- page-objects [^PDDocument doc ^long p include-image-data?]
  (let [page (.getPage doc (dec (int p)))
        offset (reduce + 0.0 (map #(page-height doc %) (range 1 p)))
        out (atom [])
        ^PDFGraphicsStreamEngine engine (object-engine page p offset out include-image-data?)]
    (.processPage engine page)
    @out))

(defn- obj-bbox [o]
  [(:x0 o) (:top o) (:x1 o) (:bottom o)])

(defn objects
  "Vector of page object maps. Each is `{:type :line|:rect|:curve|:image :x0
   :top :x1 :bottom :page-number ...}`. Image maps include pixel dimensions,
   color metadata, and `:object-type :image`. Options: `:page` (1-based),
   `:types` (a set to keep), `:bbox` (keep intersecting objects), and
   `:include-image-data?` (attach decoded PNG `:bytes`; false by default)."
  ([doc] (objects doc {}))
  ([^PDDocument doc {:keys [page bbox types include-image-data? view-operations]}]
   (let [pages (if page [(long page)] (range 1 (inc (.getNumberOfPages doc))))
         all (into [] (mapcat #(page-objects doc % include-image-data?)) pages)]
     (cond-> (cond->> all
               types (filterv #(contains? types (:type %)))
               (and bbox (not view-operations))
               (filterv #(g/intersects? bbox (obj-bbox %))))
       view-operations (page/apply-view view-operations)))))

(defn images
  "Vector of drawn image objects. Accepts the same options as `objects`; decoded
   PNG `:bytes` are included only with `:include-image-data? true`."
  ([doc] (images doc {}))
  ([doc opts]
   (objects doc (assoc opts :types #{:image}))))

(defn lines
  "Dedicated collection of painted line objects."
  ([doc] (lines doc {}))
  ([doc opts] (objects doc (assoc opts :types #{:line}))))

(defn rects
  "Dedicated collection of painted rectangle objects."
  ([doc] (rects doc {}))
  ([doc opts] (objects doc (assoc opts :types #{:rect}))))

(defn curves
  "Dedicated collection of painted Bézier curve objects."
  ([doc] (curves doc {}))
  ([doc opts] (objects doc (assoc opts :types #{:curve}))))

(defn objects-by-type
  "Object collections grouped under singular type keywords."
  ([doc] (objects-by-type doc {}))
  ([doc opts] (into {} (map (fn [[type values]] [type (vec values)]))
                    (group-by :type (objects doc opts)))))

(defn- edge-record [source object-type [x0 top] [x1 bottom]]
  (let [lo-x (min x0 x1) hi-x (max x0 x1)
        lo-top (min top bottom) hi-bottom (max top bottom)
        orientation (cond
                      (<= (- hi-bottom lo-top) orient-tolerance) :horizontal
                      (<= (- hi-x lo-x) orient-tolerance) :vertical
                      :else :other)
        page-height (when (:y0 source) (+ (:bottom source) (:y0 source)))
        doctop-offset (when (:doctop source) (- (:doctop source) (:top source)))]
    (merge (select-keys source [:page-number :linewidth :stroking-color
                                :non-stroking-color])
           {:type :line :object-type object-type
            :x0 lo-x :top lo-top :x1 hi-x :bottom hi-bottom
            :width (- hi-x lo-x) :height (- hi-bottom lo-top)
            :orientation orientation}
           (when page-height
             {:y0 (- page-height hi-bottom) :y1 (- page-height lo-top)})
           (when doctop-offset {:doctop (+ doctop-offset lo-top)}))))

(defn- rect-edges [rect]
  (let [{:keys [x0 top x1 bottom]} rect]
    [(edge-record rect :rect-edge [x0 top] [x1 top])
     (edge-record rect :rect-edge [x0 bottom] [x1 bottom])
     (edge-record rect :rect-edge [x0 top] [x0 bottom])
     (edge-record rect :rect-edge [x1 top] [x1 bottom])]))

(defn- curve-edges [curve]
  (mapv #(edge-record curve :curve-edge (first %) (second %))
        (partition 2 1 (:pts curve))))

(defn edges
  "Normalized horizontal and vertical edges from lines, rectangles, and curves."
  ([doc] (edges doc {}))
  ([doc opts]
   (let [objs (objects doc (dissoc opts :types))
         raw (concat
              (map #(assoc % :object-type :line) (filter #(= :line (:type %)) objs))
              (mapcat rect-edges (filter #(= :rect (:type %)) objs))
              (mapcat curve-edges (filter #(= :curve (:type %)) objs)))
         horizontal (filter #(= :horizontal (:orientation %)) raw)
         vertical (filter #(= :vertical (:orientation %)) raw)
         other (filter #(= :other (:orientation %)) raw)]
     (vec (concat horizontal vertical other)))))

(defn horizontal-edges
  "Horizontal subset of `edges`."
  ([doc] (horizontal-edges doc {}))
  ([doc opts] (filterv #(= :horizontal (:orientation %)) (edges doc opts))))

(defn vertical-edges
  "Vertical subset of `edges`."
  ([doc] (vertical-edges doc {}))
  ([doc opts] (filterv #(= :vertical (:orientation %)) (edges doc opts))))

(defn- field-type [^PDField field]
  (cond
    (instance? PDTextField field) :text
    (instance? PDButton field) :button
    (instance? PDChoice field) :choice
    (instance? PDSignatureField field) :signature
    :else (some-> (.getFieldType field) str/lower-case keyword)))

(defn- field-value [^PDField field]
  (when (and (instance? PDTerminalField field)
             (not (instance? PDSignatureField field)))
    (try
      (.getValueAsString field)
      (catch Exception _ nil))))

(defn- widget-field-lookup [^PDDocument doc]
  (if-let [form ^PDAcroForm (some-> doc .getDocumentCatalog .getAcroForm)]
    (reduce (fn [lookup ^PDField field]
              (reduce (fn [fields ^PDAnnotationWidget widget]
                        (assoc fields (.getCOSObject widget) field))
                      lookup
                      (.getWidgets field)))
            {}
            (.getFieldTree form))
    {}))

(defn- annotation-obj [page-height page-no doctop-offset field-lookup
                       ^PDAnnotation annotation]
  (let [rect (.getRectangle annotation)
        x0 (double (.getLowerLeftX rect))
        x1 (double (.getUpperRightX rect))
        top (- page-height (double (.getUpperRightY rect)))
        bottom (- page-height (double (.getLowerLeftY rect)))
        action (when (instance? PDAnnotationLink annotation)
                 (.getAction ^PDAnnotationLink annotation))
        uri (when (instance? PDActionURI action) (.getURI ^PDActionURI action))
        title (when (instance? PDAnnotationMarkup annotation)
                (.getTitlePopup ^PDAnnotationMarkup annotation))
        field (when (instance? PDAnnotationWidget annotation)
                (get field-lookup (.getCOSObject ^PDAnnotationWidget annotation)))]
    (cond-> {:type :annot :object-type :annot
             :subtype (.getSubtype annotation)
             :x0 x0 :top top :x1 x1 :bottom bottom
             :y0 (- page-height bottom) :y1 (- page-height top)
             :width (- x1 x0) :height (- bottom top)
             :doctop (+ doctop-offset top)
             :page-number page-no}
      (.getContents annotation) (assoc :contents (.getContents annotation))
      title (assoc :title title)
      uri (assoc :uri uri)
      field (assoc :field-name (.getFullyQualifiedName ^PDField field)
                   :field-value (field-value field)
                   :field-type (field-type field)))))

(defn annots
  "All page annotations with positional pdfplumber attributes."
  ([doc] (annots doc {}))
  ([^PDDocument doc {:keys [page bbox view-operations]}]
   (let [pages (if page [(long page)] (range 1 (inc (.getNumberOfPages doc))))
         field-lookup (widget-field-lookup doc)
         all (into []
                   (mapcat (fn [p]
                             (let [pd-page (.getPage doc (dec (int p)))
                                   height (page-height doc p)
                                   offset (reduce + 0.0
                                                  (map #(page-height doc %) (range 1 p)))]
                               (map #(annotation-obj height p offset field-lookup %)
                                    (.getAnnotations pd-page)))))
                   pages)]
     (cond-> (if (and bbox (not view-operations))
               (filterv #(g/intersects? bbox (obj-bbox %)) all)
               all)
       view-operations (page/apply-view view-operations)))))

(defn hyperlinks
  "URI link annotations only."
  ([doc] (hyperlinks doc {}))
  ([doc opts] (filterv :uri (annots doc opts))))
