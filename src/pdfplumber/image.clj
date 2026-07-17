(ns pdfplumber.image
  "Page raster rendering and visual debugging overlays."
  (:refer-clojure :exclude [copy reset])
  (:require [pdfplumber.document :as document]
            [pdfplumber.geometry :as geometry]
            [pdfplumber.objects :as objects]
            [pdfplumber.page :as page]
            [pdfplumber.table :as table]
            [pdfplumber.text :as text])
  (:import [java.awt BasicStroke Color Graphics2D GraphicsEnvironment RenderingHints]
           [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO]
           [javax.swing ImageIcon JFrame JLabel WindowConstants]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering PDFRenderer]))

(set! *warn-on-reflection* true)

(defrecord PageImage [^BufferedImage image source opts scale root width height])

(defn page-image?
  "True when `x` is a PageImage."
  [x]
  (instance? PageImage x))

(defn- antialias! [^Graphics2D graphics antialias?]
  (.setRenderingHint graphics RenderingHints/KEY_ANTIALIASING
                     (if antialias?
                       RenderingHints/VALUE_ANTIALIAS_ON
                       RenderingHints/VALUE_ANTIALIAS_OFF))
  (.setRenderingHint graphics RenderingHints/KEY_TEXT_ANTIALIASING
                     (if antialias?
                       RenderingHints/VALUE_TEXT_ANTIALIAS_ON
                       RenderingHints/VALUE_TEXT_ANTIALIAS_OFF)))

(defn- configure-renderer! [^PDFRenderer renderer antialias?]
  (let [hints (RenderingHints. RenderingHints/KEY_ANTIALIASING
                               (if antialias?
                                 RenderingHints/VALUE_ANTIALIAS_ON
                                 RenderingHints/VALUE_ANTIALIAS_OFF))]
    (.put hints RenderingHints/KEY_TEXT_ANTIALIASING
          (if antialias?
            RenderingHints/VALUE_TEXT_ANTIALIAS_ON
            RenderingHints/VALUE_TEXT_ANTIALIAS_OFF))
    (.setRenderingHints renderer hints)))

(defn- copy-raster ^BufferedImage [^BufferedImage source]
  (let [image-type (if (zero? (.getType source))
                     BufferedImage/TYPE_INT_ARGB
                     (.getType source))
        result (BufferedImage. (.getWidth source) (.getHeight source) image-type)
        ^Graphics2D graphics (.createGraphics result)]
    (try
      (.drawImage graphics source 0 0 nil)
      result
      (finally
        (.dispose graphics)))))

(defn- crop-raster ^BufferedImage [^BufferedImage source bbox scale]
  (let [[x0 top x1 bottom] bbox
        left (max 0 (int (Math/round (* (double scale) (double x0)))))
        upper (max 0 (int (Math/round (* (double scale) (double top)))))
        right (min (.getWidth source)
                   (int (Math/round (* (double scale) (double x1)))))
        lower (min (.getHeight source)
                   (int (Math/round (* (double scale) (double bottom)))))
        width (- right left)
        height (- lower upper)]
    (when (or (not (pos? width)) (not (pos? height)))
      (throw (ex-info "Crop bbox falls outside the rendered page"
                      {:pdfplumber/error :invalid-crop
                       :bbox bbox})))
    (copy-raster (.getSubimage source left upper width height))))

(defn to-image
  "Render a page or cropped page view as a PageImage."
  ([source] (to-image source {}))
  ([source opts]
   (let [requested (merge {:resolution 72.0 :antialias? true} opts)
         [doc resolved] (page/resolve-source source requested)
         ^PDDocument doc doc
         resolution (double (:resolution resolved))
         _ (when-not (pos? resolution)
             (throw (ex-info "Resolution must be positive"
                             {:pdfplumber/error :invalid-resolution
                              :resolution resolution})))
         scale (/ resolution 72.0)
         page-number (long (or (:page resolved) 1))
         page-data (document/page doc page-number)
         ^PDFRenderer renderer (PDFRenderer. doc)
         _ (configure-renderer! renderer (:antialias? resolved))
         ^BufferedImage full (.renderImageWithDPI renderer (dec (int page-number))
                                                       (float resolution))
         view-bbox (:view-bbox resolved)
         ^BufferedImage raster (if view-bbox
                                 (crop-raster full view-bbox scale)
                                 full)
         root (if view-bbox
                [(first view-bbox) (second view-bbox)]
                [0.0 0.0])
         width (if view-bbox (geometry/bbox-width view-bbox) (:width page-data))
         height (if view-bbox (geometry/bbox-height view-bbox) (:height page-data))]
     (->PageImage raster source resolved scale root width height))))

(defn- ->px [pi x y]
  (let [[root-x root-y] (:root pi)
        scale (double (:scale pi))]
    [(int (Math/round (* scale (- (double x) (double root-x)))))
     (int (Math/round (* scale (- (double y) (double root-y)))))]))

(defn- ->bbox [object]
  (if (map? object)
    [(:x0 object) (:top object) (:x1 object) (:bottom object)]
    object))

(defn- ->color ^Color [value]
  (cond
    (nil? value) nil
    (instance? Color value) value
    (and (sequential? value) (#{3 4} (count value)))
    (let [[r g b a] value]
      (Color. (int r) (int g) (int b) (int (or a 255))))
    :else
    (throw (ex-info "Color must be java.awt.Color, [r g b], or [r g b a]"
                    {:pdfplumber/error :invalid-color :color value}))))

(defn- with-graphics [pi f]
  (let [^BufferedImage raster (:image pi)
        ^Graphics2D graphics (.createGraphics raster)]
    (try
      (antialias! graphics (get-in pi [:opts :antialias?] true))
      (f graphics)
      pi
      (finally
        (.dispose graphics)))))

(defn- stroke! [^Graphics2D graphics stroke stroke-width draw-fn]
  (when-let [^Color color (->color stroke)]
    (.setColor graphics color)
    (.setStroke graphics (BasicStroke. (float stroke-width)))
    (draw-fn graphics)))

(defn draw-line
  "Draw a line from two points, a bbox, or a line-object map."
  ([pi line] (draw-line pi line {}))
  ([pi line opts]
   (let [[[x0 y0] [x1 y1]] (if (and (sequential? line)
                                     (= 2 (count line))
                                     (sequential? (first line)))
                              line
                              (let [[x0 top x1 bottom] (->bbox line)]
                                [[x0 top] [x1 bottom]]))
         [px0 py0] (->px pi x0 y0)
         [px1 py1] (->px pi x1 y1)
         stroke (if (contains? opts :stroke) (:stroke opts) [255 0 0 255])
         stroke-width (double (or (:stroke-width opts) 1.0))]
     (with-graphics pi
       (fn [^Graphics2D graphics]
         (stroke! graphics stroke stroke-width
                  (fn [^Graphics2D g] (.drawLine g px0 py0 px1 py1))))))))

(defn draw-vline
  "Draw a full-height vertical rule at `x`."
  ([pi x] (draw-vline pi x {}))
  ([pi x opts]
   (let [[_ top] (:root pi)]
     (draw-line pi [[x top] [x (+ (double top) (double (:height pi)))]] opts))))

(defn draw-hline
  "Draw a full-width horizontal rule at `y`."
  ([pi y] (draw-hline pi y {}))
  ([pi y opts]
   (let [[left _] (:root pi)]
     (draw-line pi [[left y] [(+ (double left) (double (:width pi))) y]] opts))))

(defn draw-rect
  "Draw a bbox or bounded object with optional fill and stroke."
  ([pi object] (draw-rect pi object {}))
  ([pi object opts]
   (let [[x0 top x1 bottom] (->bbox object)
         [left upper] (->px pi x0 top)
         [right lower] (->px pi x1 bottom)
         width (- right left)
         height (- lower upper)
         stroke (if (contains? opts :stroke) (:stroke opts) [255 0 0 255])
         fill (if (contains? opts :fill) (:fill opts) [255 0 0 50])
         stroke-width (double (or (:stroke-width opts) 1.0))]
     (with-graphics pi
       (fn [^Graphics2D graphics]
         (when-let [^Color color (->color fill)]
           (.setColor graphics color)
           (.fillRect graphics left upper width height))
         (stroke! graphics stroke stroke-width
                  (fn [^Graphics2D g] (.drawRect g left upper width height))))))))

(defn draw-rects
  "Draw each bbox or bounded object in `items`."
  ([pi items] (draw-rects pi items {}))
  ([pi items opts]
   (reduce #(draw-rect %1 %2 opts) pi items)))

(defn draw-circle
  "Draw a circle centered at `[x y]`."
  ([pi point] (draw-circle pi point {}))
  ([pi [x y] opts]
   (let [[center-x center-y] (->px pi x y)
         radius (double (or (:radius opts) 3.0))
         pixel-radius (int (Math/round (* (double (:scale pi)) radius)))
         diameter (* 2 pixel-radius)
         left (- center-x pixel-radius)
         upper (- center-y pixel-radius)
         stroke (if (contains? opts :stroke) (:stroke opts) [255 0 0 255])
         fill (if (contains? opts :fill) (:fill opts) [255 0 0 50])
         stroke-width (double (or (:stroke-width opts) 1.0))]
     (with-graphics pi
       (fn [^Graphics2D graphics]
         (when-let [^Color color (->color fill)]
           (.setColor graphics color)
           (.fillOval graphics left upper diameter diameter))
         (stroke! graphics stroke stroke-width
                  (fn [^Graphics2D g] (.drawOval g left upper diameter diameter))))))))

(defn draw-circles
  "Draw a circle at each point in `points`."
  ([pi points] (draw-circles pi points {}))
  ([pi points opts]
   (reduce #(draw-circle %1 %2 opts) pi points)))

(def ^:private style-keys #{:stroke :fill :stroke-width :radius})

(defn- extraction-parts [pi opts]
  (let [[doc resolved] (page/resolve-source (:source pi)
                                            (merge (:opts pi) opts))]
    [doc resolved (select-keys opts style-keys)]))

(defn outline-words
  "Outline words extracted from the PageImage source."
  ([pi] (outline-words pi {}))
  ([pi opts]
   (let [[doc extraction style] (extraction-parts pi opts)]
     (draw-rects pi (text/words doc extraction)
                 (merge {:fill nil} style)))))

(defn outline-chars
  "Outline characters extracted from the PageImage source."
  ([pi] (outline-chars pi {}))
  ([pi opts]
   (let [[doc extraction style] (extraction-parts pi opts)]
     (draw-rects pi (text/chars doc extraction)
                 (merge {:fill nil} style)))))

(defn- edge-intersections [edges]
  (let [horizontal (filter #(= :horizontal (:orientation %)) edges)
        vertical (filter #(= :vertical (:orientation %)) edges)]
    (vec
     (for [h horizontal
           v vertical
           :let [x (:x0 v) y (:top h)]
           :when (and (<= (:x0 h) x (:x1 h))
                      (<= (:top v) y (:bottom v)))]
       [x y]))))

(defn debug-tablefinder
  "Overlay detected edges, intersections, tables, and cells."
  ([pi] (debug-tablefinder pi {}))
  ([pi table-settings]
   (let [[doc resolved] (page/resolve-source (:source pi)
                                             (merge (:opts pi) table-settings))
         edges (objects/edges doc resolved)
         tables (table/find-tables doc resolved)
         with-edges (reduce #(draw-line %1 %2 {:stroke [255 0 0 255]})
                            pi edges)]
     (draw-rects
      (draw-rects
       (draw-circles
        with-edges
        (edge-intersections edges)
        {:radius 3 :stroke [0 0 255 255] :fill [0 0 255 180]})
       (map :bbox tables)
       {:stroke [0 0 255 255] :fill nil :stroke-width 2})
      (mapcat :cells tables)
      {:stroke [0 128 255 255] :fill [0 128 255 30]}))))

(defn reset
  "Re-render the PageImage source without overlays."
  [pi]
  (to-image (:source pi) (:opts pi)))

(defn copy
  "Deep-copy a PageImage and its raster."
  [pi]
  (assoc pi :image (copy-raster ^BufferedImage (:image pi))))

(defn save
  "Write a PageImage as PNG and return the String or File destination."
  [pi dest]
  (let [^File file (if (instance? File dest) dest (File. ^String dest))]
    (when-not (ImageIO/write ^BufferedImage (:image pi) "png" file)
      (throw (ex-info "No PNG writer is available"
                      {:pdfplumber/error :png-writer-unavailable})))
    dest))

(defn show
  "Open a Swing preview window for a PageImage."
  [pi]
  (when (GraphicsEnvironment/isHeadless)
    (throw (ex-info "Cannot show PageImage in a headless environment"
                    {:pdfplumber/error :headless-environment})))
  (let [frame (JFrame. "pdfplumber PageImage")
        label (JLabel. (ImageIcon. ^BufferedImage (:image pi)))]
    (.setDefaultCloseOperation frame WindowConstants/DISPOSE_ON_CLOSE)
    (.add (.getContentPane frame) label)
    (.pack frame)
    (.setLocationByPlatform frame true)
    (.setVisible frame true)
    frame))
