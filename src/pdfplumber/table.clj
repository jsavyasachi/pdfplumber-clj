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

;; --- :text strategy -------------------------------------------------------

(defn- clusters
  "Group sorted `vals` into runs where neighbours are within `tol`."
  [vals tol]
  (->> (sort vals)
       (reduce (fn [acc v]
                 (if (and (seq acc) (<= (- v (peek (peek acc))) tol))
                   (conj (pop acc) (conj (peek acc) v))
                   (conj acc [v])))
               [])))

(defn- text-rows [words y-tol]
  (->> (sort-by :top words)
       (reduce (fn [acc w]
                 (let [row (peek acc)
                       rtop (some-> row first :top)]
                   (if (and rtop (<= (Math/abs (- (double (:top w)) (double rtop))) y-tol))
                     (conj (pop acc) (conj row w))
                     (conj acc [w]))))
               [])))

(defn- column-edges
  "Left edges of columns: clusters of word `:x0` supported by at least
   `min-vertical` words."
  [words x-tol min-vertical]
  (->> (clusters (map :x0 words) x-tol)
       (filter #(>= (count %) min-vertical))
       (mapv #(/ (reduce + %) (count %)))
       sort
       vec))

(defn- column-bands [edges max-x]
  (mapv vec (partition 2 1 (conj edges (inc (double max-x))))))

(defn- center-x [w] (/ (+ (double (:x0 w)) (:x1 w)) 2))

(defn- text-cell [row [left right]]
  (let [ws (filter #(let [cx (center-x %)] (and (<= left cx) (< cx right))) row)]
    {:text (->> ws (sort-by :x0) (map :text) (str/join " "))
     :bbox (when (seq ws)
             [(reduce min (map :x0 ws)) (reduce min (map :top ws))
              (reduce max (map :x1 ws)) (reduce max (map :bottom ws))])}))

(defn- words-bbox [words]
  (when (seq words)
    [(reduce min (map :x0 words)) (reduce min (map :top words))
     (reduce max (map :x1 words)) (reduce max (map :bottom words))]))

(defn- text-table [doc opts]
  (let [{:keys [text-x-tolerance text-y-tolerance min-words-vertical min-words-horizontal]
         :or {text-x-tolerance default-tolerance text-y-tolerance default-tolerance
              min-words-vertical 3 min-words-horizontal 1}} opts
        words (text/words doc opts)
        rows (text-rows words text-y-tolerance)
        edges (column-edges words text-x-tolerance min-words-vertical)
        bands (column-bands edges (reduce max 0.0 (map :x1 words)))
        result (->> rows
                    (sort-by (comp :top first))
                    (mapv (fn [row] (mapv #(text-cell row %) bands))))]
    {:page-number (or (:page opts) (:page-number (first words)) 1)
     :strategy :text
     :bbox (words-bbox words)
     :rows (filterv #(>= (count (remove (comp str/blank? :text) %)) min-words-horizontal)
                    result)
     :debug {:rows (count rows) :columns (count bands)}}))

(defn extract-table
  "Extract a single table as `{:page-number :strategy :bbox :rows :cells :debug}`.
   `:rows` is a vector of rows, each a vector of `{:text :bbox}` cells. Options:
   `:page`, `:strategy` (`:lines` default, or `:text`), `:snap-tolerance`
   (`:lines`, default 3.0), and for `:text`: `:text-x-tolerance`,
   `:text-y-tolerance`, `:min-words-vertical` (3), `:min-words-horizontal` (1).

   The `:text` strategy is heuristic and intended for digitally generated PDFs."
  ([doc] (extract-table doc {}))
  ([doc {:keys [strategy snap-tolerance]
         :or {strategy :lines snap-tolerance default-tolerance}
         :as opts}]
   (case strategy
     :lines (lines-table doc opts snap-tolerance)
     :text (text-table doc opts)
     (throw (ex-info (str "Unknown table strategy: " strategy)
                     {:pdfplumber/error :unknown-strategy :strategy strategy})))))

(defn extract-tables
  "Extract tables on the page as a vector. v1 returns at most one table (the
   bounding grid). Same options as `extract-table`."
  ([doc] (extract-tables doc {}))
  ([doc opts]
   (let [t (extract-table doc opts)]
     (if (seq (:rows t)) [t] []))))
