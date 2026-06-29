(ns pdfplumber.objects
  "Geometric object extraction (lines, rectangles, curves) via a
   PDFGraphicsStreamEngine subclass.

   PDFBox delivers path coordinates already transformed by the CTM into page
   space (bottom-left origin); we collect painted subpaths and flip them to the
   public top-left coordinate system. Only painted paths (stroked/filled) yield
   objects; clip-only / no-paint paths are discarded."
  (:require [pdfplumber.geometry :as g])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.contentstream PDFGraphicsStreamEngine]
           [java.awt.geom Point2D Point2D$Float]))

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

(defn- object-engine
  "A PDFGraphicsStreamEngine that appends top-left object maps to `out`."
  ^PDFGraphicsStreamEngine [^PDPage page ^long page-no out]
  (let [page-h (double (.getHeight (.getMediaBox page)))
        st (atom {:cur nil :start nil :lines [] :rects [] :curves []})
        flush! (fn []
                 (let [{:keys [lines rects curves]} @st]
                   (doseq [[a b] lines] (swap! out conj (line-obj page-h page-no a b)))
                   (doseq [r rects] (swap! out conj (rect-obj page-h page-no r)))
                   (doseq [c curves] (swap! out conj (curve-obj page-h page-no c)))
                   (swap! st assoc :lines [] :rects [] :curves [])))
        reset! (fn [] (swap! st assoc :lines [] :rects [] :curves []))]
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
      (endPath [] (reset!))
      (strokePath [] (flush!))
      (fillPath [_winding-rule] (flush!))
      (fillAndStrokePath [_winding-rule] (flush!))
      (drawImage [_pd-image])
      (clip [_winding-rule])
      (shadingFill [_shading-name]))))

(defn- page-objects [^PDDocument doc ^long p]
  (let [page (.getPage doc (dec (int p)))
        out (atom [])
        ^PDFGraphicsStreamEngine engine (object-engine page p out)]
    (.processPage engine page)
    @out))

(defn- obj-bbox [o]
  [(:x0 o) (:top o) (:x1 o) (:bottom o)])

(defn objects
  "Vector of geometric object maps. Each is `{:type :line|:rect|:curve :x0 :top
   :x1 :bottom :page-number ...}`; lines also carry `:orientation`
   (`:horizontal`/`:vertical`/`:other`). Options: `:page` (1-based), `:types`
   (a set to keep), and `:bbox` (keep objects intersecting it)."
  ([doc] (objects doc {}))
  ([^PDDocument doc {:keys [page bbox types]}]
   (let [pages (if page [(long page)] (range 1 (inc (.getNumberOfPages doc))))
         all (into [] (mapcat #(page-objects doc %)) pages)]
     (cond->> all
       types (filterv #(contains? types (:type %)))
       bbox (filterv #(g/intersects? bbox (obj-bbox %)))))))
