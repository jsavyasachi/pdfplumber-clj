(ns pdfplumber.objects
  "Extract page objects with a PDFGraphicsStreamEngine subclass. Objects include
   lines, rectangles, curves, and images.

   PDFBox delivers path coordinates already transformed by the CTM into page
   space (bottom-left origin); we collect painted subpaths and flip them to the
   public top-left coordinate system. Only painted paths (stroked/filled) yield
   objects; clip-only / no-paint paths are discarded."
  (:require [pdfplumber.geometry :as g]
            [pdfplumber.page :as page]
            [clojure.string :as str])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.contentstream PDFGraphicsStreamEngine]
           [org.apache.pdfbox.contentstream.operator OperatorProcessor]
           [org.apache.pdfbox.contentstream.operator.graphics AppendRectangleToPath
            CurveTo DrawObject LineTo MoveTo]
           [org.apache.pdfbox.contentstream.operator.state Concatenate Restore Save]
           [org.apache.pdfbox.cos COSFloat COSName COSNumber]
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

(defn- pdf-float->double [value]
  (Double/parseDouble (Float/toString (float value))))

(defn- pdf-float-point [x y]
  [(pdf-float->double x) (pdf-float->double y)])

(defn- multiply-ctm [[a b c d e f] [aa bb cc dd ee ff]]
  [(+ (* a aa) (* c bb))
   (+ (* b aa) (* d bb))
   (+ (* a cc) (* c dd))
   (+ (* b cc) (* d dd))
   (+ (* a ee) (* c ff) e)
   (+ (* b ee) (* d ff) f)])

(defn- transform-point [ctm [x y]]
  (let [[a b c d e f] ctm]
    [(+ (* a x) (* c y) e)
     (+ (* b x) (* d y) f)]))

(defn- cos-number-value [value]
  (if (instance? COSFloat value)
    (let [out (ByteArrayOutputStream.)]
      (.writePDF ^COSFloat value out)
      (let [text (String. (.toByteArray out) "ISO-8859-1")]
        (Double/parseDouble text)))
    (double (.floatValue ^COSNumber value))))

(defn- precise-number? [value]
  (and (instance? COSFloat value)
       (let [out (ByteArrayOutputStream.)]
         (.writePDF ^COSFloat value out)
         (let [text (String. (.toByteArray out) "ISO-8859-1")]
           (not= text (Float/toString (.floatValue ^COSFloat value)))))))

(defn- precise-operands? [operands]
  (some precise-number? operands))

(defn- transformed-ctm? [ctm]
  (not= ctm [1.0 0.0 0.0 1.0 0.0 0.0]))

(defn- operand-numbers [operands]
  (when (every? #(instance? COSNumber %) operands)
    (mapv cos-number-value operands)))

(defn- pt [^Point2D p] [(pdf-float->double (.getX p))
                        (pdf-float->double (.getY p))])

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

(defn- rect-obj [page-h page-no doctop-offset attrs corners source]
  (let [corners (or (:precise-corners source) corners)
        xs (map first corners)
        tops (if (and (nil? (:precise-corners source))
                      source
                      (:identity-ctm? source)
                      (neg? (:height source)))
               (map #(g/flip-y page-h %)
                    [(:y source) (+ (:y source) (:height source))])
               (map #(g/flip-y page-h (second %)) corners))
        x0 (apply min xs) top (apply min tops)
        x1 (apply max xs) bottom (apply max tops)]
    (rich-bbox :rect page-h page-no doctop-offset x0 top x1 bottom attrs)))

(defn- near? [a b]
  (<= (Math/abs (- (double a) (double b))) orient-tolerance))

(defn- axis-aligned? [[x0 y0] [x1 y1]]
  (or (near? x0 x1) (near? y0 y1)))

(defn- rect-corners [subpath]
  (let [points (:points subpath)
        corners (if (and (= 5 (count points))
                         (every? true? (map near? (first points) (last points))))
                  (pop points)
                  points)]
    (when (and (:closed? subpath)
               (not (:has-curve? subpath))
               (= 4 (count corners))
               (every? #(apply axis-aligned? %) (partition 2 1 (conj (vec corners) (first corners))))
               (let [xs (map first corners)
                     ys (map second corners)
                     x0 (apply min xs) x1 (apply max xs)
                     y0 (apply min ys) y1 (apply max ys)
                     corner-ids (set (map (fn [[x y]]
                                            [(if (< (Math/abs (- (double x) (double x0)))
                                                   (Math/abs (- (double x) (double x1))))
                                               0 1)
                                             (if (< (Math/abs (- (double y) (double y0)))
                                                   (Math/abs (- (double y) (double y1))))
                                               0 1)])
                                          corners))]
                 (and (not= x0 x1)
                      (not= y0 y1)
                      (= #{[0 0] [0 1] [1 0] [1 1]} corner-ids))))
      corners)))

(defn- curve-obj [page-h page-no doctop-offset attrs points]
  (let [xs (map first points)
        tops (map #(g/flip-y page-h (second %)) points)
        pts (mapv (fn [[x y]] [(double x) (double (g/flip-y page-h y))]) points)
        x0 (apply min xs) top (apply min tops)
        x1 (apply max xs) bottom (apply max tops)]
    (assoc (rich-bbox :curve page-h page-no doctop-offset x0 top x1 bottom attrs)
           :pts pts)))

(defn- normalized-subpath [subpath]
  (let [points (:points subpath)
        ops (:ops subpath)
        n (count ops)]
    (if (and (:closed? subpath)
             (> n 3)
             (= [:line :close] (subvec ops (- n 2)))
             (= (nth points (- n 2)) (first points)))
      (assoc subpath
             :ops (conj (subvec ops 0 (- n 2)) :close)
             :points (conj (subvec points 0 (- n 2)) (last points)))
      subpath)))

(defn- png-bytes ^bytes [^PDImage image]
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write (.getImage image) "png" out)
    (.toByteArray out)))

(defn- image-obj [page-h page-no ^PDImage image ^Matrix ctm include-data?]
  (let [corners (if (vector? ctm)
                  (map #(transform-point ctm %) [[0 0] [1 0] [0 1] [1 1]])
                  (map (fn [[x y]] (pt (.transformPoint ctm (float x) (float y))))
                       [[0 0] [1 0] [0 1] [1 1]]))
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

(defn- integral-number [value]
  (let [rounded (Math/rint (double value))]
    (if (== (double value) rounded)
      (long rounded)
      value)))

(defn- rotate-object [object page-width page-height rotation doctop-offset]
  (let [[x0 top x1 bottom] (g/rotate-bbox [(:x0 object) (:top object)
                                            (:x1 object) (:bottom object)]
                                           page-width page-height rotation)
        [x0 top x1 bottom] (if (= :annot (:type object))
                             (mapv integral-number [x0 top x1 bottom])
                             [x0 top x1 bottom])
        display-height (if (contains? #{90 270} rotation)
                         page-width
                         page-height)
        object (assoc object
                      :x0 x0 :top top :x1 x1 :bottom bottom
                      :y0 (- display-height bottom)
                      :y1 (- display-height top)
                      :doctop (+ doctop-offset top))]
    (cond-> object
      (contains? object :pts)
      (update :pts #(mapv (fn [point]
                            (g/rotate-point point page-width page-height rotation))
                          %))

      (not= :image (:type object))
      (assoc :width (- x1 x0) :height (- bottom top))

      (= :line (:type object))
      (assoc :orientation (cond
                            (<= (- bottom top) orient-tolerance) :horizontal
                            (<= (- x1 x0) orient-tolerance) :vertical
                            :else :other)))))

(defn- subpath-obj [page-h page-no doctop-offset attrs subpath]
  (let [{:keys [points ops] :as subpath} (normalized-subpath subpath)]
    (when (seq points)
      (if-let [corners (rect-corners subpath)]
        (rect-obj page-h page-no doctop-offset attrs corners nil)
        (if (and (not (:has-curve? subpath))
                 (or (= [:move :line] ops)
                     (= [:move :line :close] ops)))
          (line-obj page-h page-no doctop-offset attrs (first points) (second points))
          (curve-obj page-h page-no doctop-offset attrs points))))))

(defn- object-engine
  "A PDFGraphicsStreamEngine that adds top-left object maps to `out`."
  ^PDFGraphicsStreamEngine [^PDPage page page-no doctop-offset out include-image-data?]
  (let [page-h (pdf-float->double (.getHeight (.getMediaBox page)))
        st (atom {:cur nil :start nil :subpaths [] :rects []})
        pending-rectangle (atom nil)
        precise-ctm (atom [1.0 0.0 0.0 1.0 0.0 0.0])
        precise-ctm-stack (atom [])
        initial-ctm? (atom true)
        precise-ctm? (atom false)
        pending-path (atom nil)
        pending-image-ctm (atom nil)
        engine-holder (atom nil)
        flush! (fn []
                 (let [{:keys [subpaths rects]} @st
                       subpaths (if (= 1 (count subpaths))
                                  subpaths
                                  (remove #(= 1 (count (:points %))) subpaths))
                       attrs (paint-attrs ^PDFGraphicsStreamEngine @engine-holder)]
                   (doseq [subpath subpaths]
                     (when-let [object (subpath-obj page-h page-no doctop-offset attrs subpath)]
                       (swap! out conj object)))
                   (doseq [{:keys [corners extra-close? source]} rects]
                     (swap! out conj
                            (if extra-close?
                              (curve-obj page-h page-no doctop-offset attrs
                                         (conj corners (first corners) (first corners)))
                              (rect-obj page-h page-no doctop-offset attrs corners source))))
                   (swap! st assoc :cur nil :start nil :subpaths [] :rects [])))
        clear! (fn [] (swap! st assoc :cur nil :start nil :subpaths [] :rects []))
        engine
        (proxy [PDFGraphicsStreamEngine] [page]
          (appendRectangle [p0 p1 p2 p3]
            (let [source @pending-rectangle]
              (reset! pending-rectangle nil)
              (swap! st update :rects conj {:corners (mapv pt [p0 p1 p2 p3])
                                            :extra-close? false
                                            :source source})))
          (moveTo [x y]
            (let [point (or (when (= :move (:kind @pending-path))
                              (:point @pending-path))
                            (pdf-float-point x y))]
              (reset! pending-path nil)
              (swap! st (fn [s] (-> s
                                    (assoc :cur point :start point)
                                    (update :subpaths conj {:points [point]
                                                            :ops [:move]
                                                            :closed? false}))))))
          (lineTo [x y]
            (let [point (or (when (= :line (:kind @pending-path))
                              (:point @pending-path))
                            (pdf-float-point x y))]
              (reset! pending-path nil)
              (swap! st (fn [s] (-> s
                                    (update-in [:subpaths (dec (count (:subpaths s))) :points]
                                               conj point)
                                    (update-in [:subpaths (dec (count (:subpaths s))) :ops] conj :line)
                                    (assoc :cur point))))))
          (curveTo [x1 y1 x2 y2 x3 y3]
            (let [points (or (when (= :curve (:kind @pending-path))
                               (:points @pending-path))
                             [(pdf-float-point x1 y1)
                              (pdf-float-point x2 y2)
                              (pdf-float-point x3 y3)])]
              (reset! pending-path nil)
              (swap! st (fn [s] (-> s
                                    (assoc-in [:subpaths (dec (count (:subpaths s))) :has-curve?] true)
                                    (update-in [:subpaths (dec (count (:subpaths s))) :points]
                                               conj (last points))
                                    (update-in [:subpaths (dec (count (:subpaths s))) :ops] conj :curve)
                                    (assoc :cur (last points)))))))
          (getCurrentPoint []
            (let [[x y] (or (:cur @st) [0.0 0.0])]
              (Point2D$Float. (float x) (float y))))
          (closePath []
            (swap! st (fn [s] (cond-> s
                                (and (:cur s) (:start s) (seq (:subpaths s)))
                                (-> (assoc :cur (:start s))
                                    (assoc-in [:subpaths (dec (count (:subpaths s))) :closed?] true)
                                    (update-in [:subpaths (dec (count (:subpaths s))) :points] conj (:start s))
                                    (update-in [:subpaths (dec (count (:subpaths s))) :ops] conj :close))
                                (and (nil? (:cur s)) (seq (:rects s)))
                                (assoc-in [:rects (dec (count (:rects s))) :extra-close?] true)))))
          (endPath [] (clear!))
          (strokePath [] (flush!))
          (fillPath [_winding-rule] (flush!))
          (fillAndStrokePath [_winding-rule] (flush!))
          (drawImage [pd-image]
            (let [ctm (or @pending-image-ctm
                          (.getCurrentTransformationMatrix
                           (.getGraphicsState ^PDFGraphicsStreamEngine @engine-holder)))
                  _ (reset! pending-image-ctm nil)]
              (swap! out conj
                     (image-obj page-h page-no pd-image
                                ctm
                                include-image-data?))))
          (clip [_winding-rule])
          (shadingFill [_shading-name]))]
    (clojure.core/reset! engine-holder engine)
    (let [rectangle-processor (AppendRectangleToPath. engine)
          curve-processor (CurveTo. engine)
          draw-processor (DrawObject. engine)
          line-processor (LineTo. engine)
          move-processor (MoveTo. engine)
          concatenate-processor (Concatenate. engine)
          restore-processor (Restore. engine)
          save-processor (Save. engine)]
      (letfn [(register! [name handler]
                (.addOperator engine
                              (proxy [OperatorProcessor] [engine]
                                (process [operator operands]
                                  (handler operator operands))
                                (getName [] name))))]
        (register! "q"
                   (fn [operator operands]
                     (swap! precise-ctm-stack conj @precise-ctm)
                     (.process save-processor operator operands)))
        (register! "Q"
                   (fn [operator operands]
                     (.process restore-processor operator operands)
                     (reset! precise-ctm (or (peek @precise-ctm-stack)
                                             [1.0 0.0 0.0 1.0 0.0 0.0]))
                     (swap! precise-ctm-stack pop)))
        (register! "cm"
                   (fn [operator operands]
                     (when-let [numbers (operand-numbers operands)]
                       (when (= 6 (count numbers))
                         (let [precise? (precise-operands? operands)
                               numbers (if (or @initial-ctm? (not precise?))
                                         (mapv #(pdf-float->double (float %)) numbers)
                                         numbers)]
                           (swap! precise-ctm multiply-ctm numbers)
                           (when (and precise? (not @initial-ctm?))
                             (reset! precise-ctm? true))))
                       (reset! initial-ctm? false))
                     (.process concatenate-processor operator operands)))
        (register! "m"
                   (fn [operator operands]
                     (when-let [numbers (operand-numbers operands)]
                       (if (and (= 2 (count numbers))
                                (or (precise-operands? operands) @precise-ctm?)
                                (transformed-ctm? @precise-ctm))
                         (reset! pending-path
                                 {:kind :move
                                  :point (transform-point @precise-ctm numbers)})
                         (reset! pending-path nil)))
                     (.process move-processor operator operands)))
        (register! "l"
                   (fn [operator operands]
                     (when-let [numbers (operand-numbers operands)]
                       (if (and (= 2 (count numbers))
                                (or (precise-operands? operands) @precise-ctm?)
                                (transformed-ctm? @precise-ctm))
                         (reset! pending-path
                                 {:kind :line
                                  :point (transform-point @precise-ctm numbers)})
                         (reset! pending-path nil)))
                     (.process line-processor operator operands)))
        (register! "c"
                   (fn [operator operands]
                     (when-let [numbers (operand-numbers operands)]
                       (if (and (= 6 (count numbers))
                                (or (precise-operands? operands) @precise-ctm?)
                                (transformed-ctm? @precise-ctm))
                         (reset! pending-path
                                 {:kind :curve
                                  :points (mapv #(transform-point @precise-ctm %)
                                                (partition 2 numbers))})
                         (reset! pending-path nil)))
                     (.process curve-processor operator operands)))
        (register! "re"
                   (fn [operator operands]
                     (if-let [numbers (operand-numbers operands)]
                       (if (and (= 4 (count numbers))
                                (or (precise-operands? operands) @precise-ctm?)
                                (transformed-ctm? @precise-ctm)
                                (not (neg? (nth numbers 3))))
                         (let [[x y width height] numbers]
                           (reset! pending-rectangle
                                   {:precise-corners
                                    (mapv #(transform-point @precise-ctm %)
                                          [[x y]
                                           [(+ x width) y]
                                           [(+ x width) (+ y height)]
                                           [x (+ y height)]])}))
                         (let [[_ y _ height] operands]
                           (reset! pending-rectangle
                                   {:y (pdf-float->double (.floatValue ^COSNumber y))
                                    :height (pdf-float->double (.floatValue ^COSNumber height))
                                    :identity-ctm? (.equals
                                                    (.getCurrentTransformationMatrix
                                                     (.getGraphicsState ^PDFGraphicsStreamEngine engine))
                                                    (Matrix.))})))
                       (reset! pending-rectangle nil))
                     (.process rectangle-processor operator operands)))
        (register! "Do"
                   (fn [operator operands]
                     (when @precise-ctm?
                       (reset! pending-image-ctm @precise-ctm))
                     (.process draw-processor operator operands))))
    engine)))

(defn- page-display-height [^PDDocument doc ^long p]
  (let [page (.getPage doc (dec (int p)))
        box (.getMediaBox page)]
    (if (contains? #{90 270} (mod (.getRotation page) 360))
      (pdf-float->double (.getWidth box))
      (pdf-float->double (.getHeight box)))))

(defn- page-objects [^PDDocument doc ^long p include-image-data?]
  (let [page (.getPage doc (dec (int p)))
        box (.getMediaBox page)
        page-width (pdf-float->double (.getWidth box))
        page-height (pdf-float->double (.getHeight box))
        rotation (mod (.getRotation page) 360)
        offset (reduce + 0.0 (map #(page-display-height doc %) (range 1 p)))
        out (atom [])
        ^PDFGraphicsStreamEngine engine (object-engine page p offset out include-image-data?)]
    (.processPage engine page)
    (mapv #(rotate-object % page-width page-height rotation offset) @out)))

(defn- obj-bbox [o]
  [(:x0 o) (:top o) (:x1 o) (:bottom o)])

(defn objects
  "Vector of page object maps. Each map is `{:type :line|:rect|:curve|:image :x0
   :top :x1 :bottom :page-number ...}`. Image maps include pixel dimensions,
   color metadata, and `:object-type :image`. Options: `:page` (1-based),
   `:types` (a set to keep), `:bbox` (keep intersecting objects), and
   `:include-image-data?` (attach decoded PNG `:bytes`; false by default)."
  ([doc] (objects doc {}))
  ([^PDDocument doc {:keys [page bbox types include-image-data? view-operations]}]
   (let [pages (if page [(long page)] (range 1 (inc (.getNumberOfPages doc))))
         all (into [] (mapcat #(page-objects doc % include-image-data?) pages))]
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
  "Object collections grouped by singular type keywords."
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
        x0 (pdf-float->double (.getLowerLeftX rect))
        x1 (pdf-float->double (.getUpperRightX rect))
        top (- page-height (pdf-float->double (.getUpperRightY rect)))
        bottom (- page-height (pdf-float->double (.getLowerLeftY rect)))
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
                                   box (.getMediaBox pd-page)
                                   width (pdf-float->double (.getWidth box))
                                   height (pdf-float->double (.getHeight box))
                                   rotation (mod (.getRotation pd-page) 360)
                                   offset (reduce + 0.0
                                                  (map #(page-display-height doc %) (range 1 p)))]
                               (map #(rotate-object
                                      (annotation-obj height p offset field-lookup %)
                                      width height rotation offset)
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
