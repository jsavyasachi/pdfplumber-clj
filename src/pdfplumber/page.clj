(ns pdfplumber.page
  "Composable derived-page views for crop, bbox, outside, and predicate filters."
  (:refer-clojure :exclude [filter])
  (:require [pdfplumber.geometry :as g])
  (:import [org.apache.pdfbox.pdmodel PDDocument]))

(defn page-view?
  "True when `x` is a derived page view."
  [x]
  (boolean (and (map? x) (::view x))))

(defn- source-parts [source opts]
  (if (page-view? source)
    [(:document source)
     (merge (:options source) opts)
     (:operations source)
     (:bbox source)]
    [source opts [] nil]))

(defn- page-bbox [^PDDocument doc page-number]
  (let [page (.getPage doc (dec (int page-number)))
        box (.getMediaBox page)
        rotation (mod (.getRotation page) 360)
        x0 (double (min (.getLowerLeftX box) (.getUpperRightX box)))
        y0 (double (min (.getLowerLeftY box) (.getUpperRightY box)))
        x1 (double (max (.getLowerLeftX box) (.getUpperRightX box)))
        y1 (double (max (.getLowerLeftY box) (.getUpperRightY box)))
        [x0 y0 x1 y1] (if (contains? #{90 270} rotation)
                        [y0 x0 y1 x1] [x0 y0 x1 y1])
        height (- y1 y0)]
    [x0 (- height y1) x1 (- height y0)]))

(defn- derived-view [source opts operation bbox]
  (let [[doc merged operations parent-bbox] (source-parts source opts)
        page-number (long (or (:page merged) 1))
        base-bbox (or parent-bbox (page-bbox doc page-number))]
    {::view true
     :document doc
     :options (assoc merged :page page-number)
     :operations (conj operations operation)
     :bbox (or bbox base-bbox)}))

(defn crop
  "Crop a page view to `bbox`, clipping partial objects. With `:relative true`,
   bbox coordinates are offsets from the parent view's top-left corner."
  ([source bbox] (crop source bbox {}))
  ([source bbox {:keys [relative] :as opts}]
   (let [[doc merged _ parent-bbox] (source-parts source opts)
         page-number (long (or (:page merged) 1))
         base (or parent-bbox (page-bbox doc page-number))
         [x0 top x1 bottom] bbox
         absolute (if relative
                    [(+ (first base) x0) (+ (second base) top)
                     (+ (first base) x1) (+ (second base) bottom)]
                    bbox)]
     (derived-view source (dissoc opts :relative)
                   {:kind :crop :bbox absolute} absolute))))

(defn crop-page
  "Backward-compatible absolute crop view. `opts` contains `:page` and `:bbox`."
  [source {:keys [bbox] :as opts}]
  (if bbox
    (crop source bbox (dissoc opts :bbox))
    (derived-view source opts {:kind :all} nil)))

(defn within-bbox
  "Keep only objects fully contained by `bbox`."
  ([source bbox] (within-bbox source bbox {}))
  ([source bbox opts]
   (derived-view source opts {:kind :within :bbox bbox} nil)))

(defn outside-bbox
  "Keep only objects that do not overlap `bbox`."
  ([source bbox] (outside-bbox source bbox {}))
  ([source bbox opts]
   (derived-view source opts {:kind :outside :bbox bbox} nil)))

(defn filter
  "Keep objects for which `pred` returns truthy."
  ([source pred] (filter source pred {}))
  ([source pred opts]
   (derived-view source opts {:kind :predicate :pred pred} nil)))

(defn- object-bbox [object]
  [(:x0 object) (:top object) (:x1 object) (:bottom object)])

(defn- overlaps? [[ax0 at ax1 ab] [bx0 bt bx1 bb]]
  (and (<= ax0 bx1) (<= bx0 ax1) (<= at bb) (<= bt ab)))

(defn- clip-object [object bbox]
  (when (overlaps? bbox (object-bbox object))
    (let [[x0 top x1 bottom] (object-bbox object)
          [cx0 ct cx1 cb] bbox
          clipped [(max x0 cx0) (max top ct) (min x1 cx1) (min bottom cb)]
          [nx0 nt nx1 nb] clipped
          page-height (when (and (:y0 object) (:bottom object))
                        (+ (:bottom object) (:y0 object)))
          doctop-offset (when (:doctop object) (- (:doctop object) top))]
      (cond-> (assoc object :x0 nx0 :top nt :x1 nx1 :bottom nb)
        (not= :image (:type object))
        (assoc :width (- nx1 nx0) :height (- nb nt))
        page-height
        (assoc :y0 (- page-height nb) :y1 (- page-height nt))
        doctop-offset
        (assoc :doctop (+ doctop-offset nt))))))

(defn apply-view
  "Apply derived-view operations in order to extracted object maps."
  [objects operations]
  (reduce (fn [items {:keys [kind bbox pred]}]
            (case kind
              :all items
              :crop (into [] (keep #(clip-object % bbox)) items)
              :within (filterv #(g/contains? bbox (object-bbox %)) items)
              :outside (filterv #(not (overlaps? bbox (object-bbox %))) items)
              :predicate (filterv pred items)))
          (vec objects) operations))

(defn resolve-source
  "Normalize a document-or-view plus extraction options into `[doc opts]`."
  [source opts]
  (if (page-view? source)
    [(:document source)
     (merge (:options source)
            {:view-operations (:operations source)
             :view-bbox (:bbox source)}
            opts)]
    [source opts]))
