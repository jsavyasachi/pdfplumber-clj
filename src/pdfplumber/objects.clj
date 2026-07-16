(ns pdfplumber.objects
  "Page object extraction (lines, rectangles, curves, and images) via a
   PDFGraphicsStreamEngine subclass.

   PDFBox delivers path coordinates already transformed by the CTM into page
   space (bottom-left origin); we collect painted subpaths and flip them to the
   public top-left coordinate system. Only painted paths (stroked/filled) yield
   objects; clip-only / no-paint paths are discarded."
  (:require [pdfplumber.geometry :as g])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.contentstream PDFGraphicsStreamEngine]
           [org.apache.pdfbox.cos COSName]
           [org.apache.pdfbox.pdmodel.graphics.image PDImage]
           [org.apache.pdfbox.util Matrix]
           [java.awt.geom Point2D Point2D$Float]
           [java.io ByteArrayOutputStream]
           [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(def ^:private orient-tolerance 0.1)

(defn- pt [^Point2D p] [(.getX p) (.getY p)])

(defn- line-obj [page-h page-no [x0 y0] [x1 y1]]
  (let [t0 (g/flip-y page-h y0)
        t1 (g/flip-y page-h y1)
        top (min t0 t1)
        bottom (max t0 t1)
        lo-x (min x0 x1)
        hi-x (max x0 x1)]
    {:type :line
     :x0 lo-x :top top :x1 hi-x :bottom bottom
     :orientation (cond
                    (<= (- bottom top) orient-tolerance) :horizontal
                    (<= (- hi-x lo-x) orient-tolerance) :vertical
                    :else :other)
     :page-number page-no}))

(defn- rect-obj [page-h page-no corners]
  (let [xs (map first corners)
        tops (map #(g/flip-y page-h (second %)) corners)]
    {:type :rect
     :x0 (apply min xs) :top (apply min tops)
     :x1 (apply max xs) :bottom (apply max tops)
     :page-number page-no}))

(defn- curve-obj [page-h page-no points]
  (let [xs (map first points)
        tops (map #(g/flip-y page-h (second %)) points)]
    {:type :curve
     :x0 (apply min xs) :top (apply min tops)
     :x1 (apply max xs) :bottom (apply max tops)
     :page-number page-no}))

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
  ^PDFGraphicsStreamEngine [^PDPage page ^long page-no out include-image-data?]
  (let [page-h (double (.getHeight (.getMediaBox page)))
        st (atom {:cur nil :start nil :lines [] :rects [] :curves []})
        engine-holder (atom nil)
        flush! (fn []
                 (let [{:keys [lines rects curves]} @st]
                   (doseq [[a b] lines] (swap! out conj (line-obj page-h page-no a b)))
                   (doseq [r rects] (swap! out conj (rect-obj page-h page-no r)))
                   (doseq [c curves] (swap! out conj (curve-obj page-h page-no c)))
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

(defn- page-objects [^PDDocument doc ^long p include-image-data?]
  (let [page (.getPage doc (dec (int p)))
        out (atom [])
        ^PDFGraphicsStreamEngine engine (object-engine page p out include-image-data?)]
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
  ([^PDDocument doc {:keys [page bbox types include-image-data?]}]
   (let [pages (if page [(long page)] (range 1 (inc (.getNumberOfPages doc))))
         all (into [] (mapcat #(page-objects doc % include-image-data?)) pages)]
     (cond->> all
       types (filterv #(contains? types (:type %)))
       bbox (filterv #(g/intersects? bbox (obj-bbox %)))))))

(defn images
  "Vector of drawn image objects. Accepts the same options as `objects`; decoded
   PNG `:bytes` are included only with `:include-image-data? true`."
  ([doc] (images doc {}))
  ([doc opts]
   (objects doc (assoc opts :types #{:image}))))
