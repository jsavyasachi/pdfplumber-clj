(ns pdfplumber.table
  "Table extraction. The `:lines` strategy reconstructs a grid from ruling lines
   (explicit line objects plus rectangle edges): near-collinear edges are snapped
   together, grid intersections are found, and cells are the rectangles whose
   four corners are all intersections. Words are assigned to cells by center."
  (:require [clojure.string :as str]
            [pdfplumber.geometry :as g]
            [pdfplumber.objects :as objects]
            [pdfplumber.text :as text]))

(def ^:private default-tolerance 3.0)

(defn- edges
  "Split objects into horizontal edges `{:y :x0 :x1}` and vertical edges
   `{:x :top :bottom}`, from line objects and rectangle sides."
  [objs]
  (let [lines (filter #(= :line (:type %)) objs)
        rects (filter #(= :rect (:type %)) objs)]
    {:h (concat
         (for [l lines :when (= :horizontal (:orientation l))]
           {:y (:top l) :x0 (:x0 l) :x1 (:x1 l)})
         (mapcat (fn [r] [{:y (:top r) :x0 (:x0 r) :x1 (:x1 r)}
                          {:y (:bottom r) :x0 (:x0 r) :x1 (:x1 r)}]) rects))
     :v (concat
         (for [l lines :when (= :vertical (:orientation l))]
           {:x (:x0 l) :top (:top l) :bottom (:bottom l)})
         (mapcat (fn [r] [{:x (:x0 r) :top (:top r) :bottom (:bottom r)}
                          {:x (:x1 r) :top (:top r) :bottom (:bottom r)}]) rects))}))

(defn- snap-positions
  "Sorted cluster centers of `vals`, merging values within `tol`."
  [vals tol]
  (->> (sort vals)
       (reduce (fn [acc v]
                 (if (and (seq acc) (<= (- v (peek (peek acc))) tol))
                   (conj (pop acc) (conj (peek acc) v))
                   (conj acc [v])))
               [])
       (mapv #(/ (reduce + %) (count %)))))

(defn- snap-to [canonicals tol v]
  (or (first (filter #(<= (Math/abs (- (double v) (double %))) tol) canonicals)) v))

(defn- build-grid [{:keys [h v]} tol]
  (let [ys (snap-positions (map :y h) tol)
        xs (snap-positions (map :x v) tol)]
    {:xs xs
     :ys ys
     :h (map #(assoc % :y (snap-to ys tol (:y %))) h)
     :v (map #(assoc % :x (snap-to xs tol (:x %))) v)}))

(defn- intersection?
  "True when a vertical and a horizontal edge both cross `(x, y)` within `tol`."
  [{:keys [h v]} x y tol]
  (and (some (fn [e] (and (<= (Math/abs (- (double (:x e)) x)) tol)
                          (<= (- (:top e) tol) y (+ (:bottom e) tol)))) v)
       (some (fn [e] (and (<= (Math/abs (- (double (:y e)) y)) tol)
                          (<= (- (:x0 e) tol) x (+ (:x1 e) tol)))) h)))

(defn- grid-cells
  "Cell bboxes `[x0 top x1 bottom]` for every adjacent x/y pair whose four
   corners are all grid intersections."
  [{:keys [xs ys] :as grid} tol]
  (for [[y0 y1] (partition 2 1 ys)
        [x0 x1] (partition 2 1 xs)
        :when (and (intersection? grid x0 y0 tol)
                   (intersection? grid x1 y0 tol)
                   (intersection? grid x0 y1 tol)
                   (intersection? grid x1 y1 tol))]
    [x0 y0 x1 y1]))

(defn- cell-text [words bbox]
  (->> words
       (filter #(g/within? bbox (g/center [(:x0 %) (:top %) (:x1 %) (:bottom %)])))
       (sort-by (juxt :top :x0))
       (map :text)
       (str/join " ")))

(defn- assemble-rows [cell-bboxes words tol]
  (->> cell-bboxes
       (group-by (fn [[_ top]] (Math/round (/ (double top) tol))))
       (sort-by key)
       (mapv (fn [[_ row]]
               (->> (sort-by first row)
                    (mapv (fn [bbox] {:text (cell-text words bbox) :bbox bbox})))))))

(defn- lines-table [doc opts tol]
  (let [objs (objects/objects doc opts)
        words (text/words doc opts)
        e (edges objs)
        grid (build-grid e tol)
        cells (grid-cells grid tol)
        bbox (when (and (seq (:xs grid)) (seq (:ys grid)))
               [(first (:xs grid)) (first (:ys grid))
                (last (:xs grid)) (last (:ys grid))])]
    {:page-number (or (:page opts) (:page-number (first words)) 1)
     :strategy :lines
     :bbox bbox
     :rows (assemble-rows cells words tol)
     :cells (vec cells)
     :debug {:horizontal-lines (count (:h e))
             :vertical-lines (count (:v e))
             :cells (count cells)}}))

(defn extract-table
  "Extract a single table as `{:page-number :strategy :bbox :rows :cells :debug}`.
   `:rows` is a vector of rows, each a vector of `{:text :bbox}` cells. Options:
   `:page`, `:strategy` (`:lines`, default), `:snap-tolerance` (default 3.0)."
  ([doc] (extract-table doc {}))
  ([doc {:keys [strategy snap-tolerance]
         :or {strategy :lines snap-tolerance default-tolerance}
         :as opts}]
   (case strategy
     :lines (lines-table doc opts snap-tolerance)
     (throw (ex-info (str "Unknown table strategy: " strategy)
                     {:pdfplumber/error :unknown-strategy :strategy strategy})))))

(defn extract-tables
  "Extract tables on the page as a vector. v1 returns at most one table (the
   bounding grid). Same options as `extract-table`."
  ([doc] (extract-tables doc {}))
  ([doc opts]
   (let [t (extract-table doc opts)]
     (if (seq (:rows t)) [t] []))))
