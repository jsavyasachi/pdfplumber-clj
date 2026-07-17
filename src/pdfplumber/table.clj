(ns pdfplumber.table
  "Table detection and extraction from ruling lines, text alignments, or
   caller-supplied explicit lines. Public coordinates use
   `[x0 top x1 bottom]` in a top-left origin."
  (:require [clojure.string :as str]
            [pdfplumber.geometry :as g]
            [pdfplumber.objects :as objects]
            [pdfplumber.text :as text])
  (:import [org.apache.pdfbox.pdmodel PDDocument]))

(def ^:private defaults
  {:vertical-strategy :lines
   :horizontal-strategy :lines
   :snap-tolerance 3.0
   :join-tolerance 3.0
   :edge-min-length 3.0
   :intersection-tolerance 3.0
   :edge-min-length-prefilter false
   :min-words-vertical 3
   :min-words-horizontal 1
   :text-x-tolerance 3.0
   :text-y-tolerance 3.0})

(def ^:private strategies #{:lines :lines-strict :text :explicit})

(defn- clusters [vals tol]
  (->> (sort vals)
       (reduce (fn [acc v]
                 (if (and (seq acc) (<= (- v (peek (peek acc))) tol))
                   (conj (pop acc) (conj (peek acc) v))
                   (conj acc [v])))
               [])))

(defn- snap-positions [vals tol]
  (mapv #(/ (reduce + %) (count %)) (clusters vals tol)))

(defn- snap-to [positions tol v]
  (or (first (filter #(<= (Math/abs (- (double v) (double %))) tol) positions)) v))

(defn- source-edges [objs strict?]
  (let [lines (filter #(= :line (:type %)) objs)
        rects (if strict? [] (filter #(= :rect (:type %)) objs))]
    {:h (concat
         (for [line lines :when (= :horizontal (:orientation line))]
           {:y (:top line) :x0 (:x0 line) :x1 (:x1 line)})
         (mapcat (fn [rect]
                   [{:y (:top rect) :x0 (:x0 rect) :x1 (:x1 rect)}
                    {:y (:bottom rect) :x0 (:x0 rect) :x1 (:x1 rect)}])
                 rects))
     :v (concat
         (for [line lines :when (= :vertical (:orientation line))]
           {:x (:x0 line) :top (:top line) :bottom (:bottom line)})
         (mapcat (fn [rect]
                   [{:x (:x0 rect) :top (:top rect) :bottom (:bottom rect)}
                    {:x (:x1 rect) :top (:top rect) :bottom (:bottom rect)}])
                 rects))}))

(defn- text-rows [words tol]
  (->> (sort-by :top words)
       (reduce (fn [rows word]
                 (let [row (peek rows)
                       row-top (some-> row first :top)]
                   (if (and row-top
                            (<= (Math/abs (- (double (:top word))
                                             (double row-top))) tol))
                     (conj (pop rows) (conj row word))
                     (conj rows [word]))))
               [])))

(defn- words-bbox [words]
  (when (seq words)
    [(reduce min (map :x0 words)) (reduce min (map :top words))
     (reduce max (map :x1 words)) (reduce max (map :bottom words))]))

(defn- supported-positions [vals tol minimum]
  (->> (clusters vals tol)
       (filter #(>= (count %) minimum))
       (mapv #(/ (reduce + %) (count %)))))

(defn- text-vertical-edges [words {:keys [text-x-tolerance min-words-vertical]}]
  (when-let [[left top right bottom] (words-bbox words)]
    (let [alignments [(map :x0 words)
                      (map :x1 words)
                      (map #(/ (+ (:x0 %) (:x1 %)) 2.0) words)]
          families (mapv #(supported-positions % text-x-tolerance
                                               min-words-vertical)
                         alignments)
          aligned (reduce (fn [best family]
                            (if (> (count family) (count best)) family best))
                          [] families)
          positions (vec (distinct (sort (concat [left right] aligned))))]
      (for [x positions] {:x x :top top :bottom bottom}))))

(defn- text-horizontal-edges [words {:keys [text-y-tolerance min-words-horizontal]}]
  (when-let [[left _ right _] (words-bbox words)]
    (let [rows (filter #(>= (count %) min-words-horizontal)
                       (text-rows words text-y-tolerance))
          positions (cond-> (mapv #(reduce min (map :top %)) rows)
                      (seq rows) (conj (reduce max (map :bottom (last rows)))))]
      (for [y (distinct (sort positions))] {:y y :x0 left :x1 right}))))

(defn- page-bbox [^PDDocument doc opts]
  (or (:view-bbox opts) (:bbox opts)
      (let [page (.getPage doc (dec (int (or (:page opts) 1))))
            box (.getMediaBox page)]
        [0.0 0.0 (double (.getWidth box)) (double (.getHeight box))])))

(defn- explicit-v-edge [entry [x0 top x1 bottom]]
  (if (number? entry)
    {:x (double entry) :top top :bottom bottom}
    {:x (double (or (:x entry) (:x0 entry)))
     :top (double (or (:top entry) top))
     :bottom (double (or (:bottom entry) bottom))}))

(defn- explicit-h-edge [entry [x0 top x1 bottom]]
  (if (number? entry)
    {:y (double entry) :x0 x0 :x1 x1}
    {:y (double (or (:y entry) (:top entry)))
     :x0 (double (or (:x0 entry) x0))
     :x1 (double (or (:x1 entry) x1))}))

(defn- join-runs [edges coord start end tolerance]
  (mapcat
   (fn [[position same-position]]
     (let [runs (reduce (fn [runs edge]
                          (let [run (peek runs)]
                            (if (and run (<= (- (double (start edge))
                                                (double (end run))) tolerance))
                              (conj (pop runs) (assoc run end (max (end run) (end edge))))
                              (conj runs edge))))
                        []
                        (sort-by start same-position))]
       (map #(assoc % coord position) runs)))
   (group-by coord edges)))

(defn- edge-length [orientation edge]
  (if (= orientation :h)
    (- (double (:x1 edge)) (:x0 edge))
    (- (double (:bottom edge)) (:top edge))))

(defn- normalize-edges [{:keys [h v]} opts]
  (let [snap-x (double (or (:snap-x-tolerance opts) (:snap-tolerance opts)))
        snap-y (double (or (:snap-y-tolerance opts) (:snap-tolerance opts)))
        join-x (double (or (:join-x-tolerance opts) (:join-tolerance opts)))
        join-y (double (or (:join-y-tolerance opts) (:join-tolerance opts)))
        edge-min-length (:edge-min-length opts)
        h (if (:edge-min-length-prefilter opts)
            (filter #(>= (edge-length :h %) edge-min-length) h) h)
        v (if (:edge-min-length-prefilter opts)
            (filter #(>= (edge-length :v %) edge-min-length) v) v)
        ys (snap-positions (map :y h) snap-y)
        xs (snap-positions (map :x v) snap-x)
        h (map #(assoc % :y (snap-to ys snap-y (:y %))) h)
        v (map #(assoc % :x (snap-to xs snap-x (:x %))) v)
        h (join-runs h :y :x0 :x1 join-x)
        v (join-runs v :x :top :bottom join-y)]
    {:h (filterv #(>= (- (double (:x1 %)) (:x0 %)) edge-min-length) h)
     :v (filterv #(>= (- (double (:bottom %)) (:top %)) edge-min-length) v)}))

(defn- strategy-edges [doc words objs opts]
  (let [bbox (page-bbox doc opts)
        line-edges (source-edges objs false)
        strict-edges (source-edges objs true)
        vertical-base (case (:vertical-strategy opts)
                   :lines (:v line-edges)
                   :lines-strict (:v strict-edges)
                   :text (text-vertical-edges words opts)
                   :explicit [])
        horizontal-base (case (:horizontal-strategy opts)
                     :lines (:h line-edges)
                     :lines-strict (:h strict-edges)
                     :text (text-horizontal-edges words opts)
                     :explicit [])
        vertical (concat vertical-base
                         (map #(explicit-v-edge % bbox)
                              (:explicit-vertical-lines opts)))
        horizontal (concat horizontal-base
                           (map #(explicit-h-edge % bbox)
                                (:explicit-horizontal-lines opts)))
        vertical (if (and (= :text (:vertical-strategy opts)) (seq horizontal))
                   (let [top (reduce min (map :y horizontal))
                         bottom (reduce max (map :y horizontal))]
                     (map #(assoc % :top top :bottom bottom) vertical))
                   vertical)
        horizontal (if (and (= :text (:horizontal-strategy opts)) (seq vertical))
                     (let [x0 (reduce min (map :x vertical))
                           x1 (reduce max (map :x vertical))]
                       (map #(assoc % :x0 x0 :x1 x1) horizontal))
                     horizontal)]
    (normalize-edges {:h horizontal :v vertical} opts)))

(defn- intersection? [{:keys [h v]} x y x-tol y-tol]
  (and (some #(and (<= (Math/abs (- (double (:x %)) x)) x-tol)
                    (<= (- (:top %) y-tol) y (+ (:bottom %) y-tol))) v)
       (some #(and (<= (Math/abs (- (double (:y %)) y)) y-tol)
                    (<= (- (:x0 %) x-tol) x (+ (:x1 %) x-tol))) h)))

(defn- grid-cells [edges x-tolerance y-tolerance]
  (let [xs (sort (distinct (map :x (:v edges))))
        ys (sort (distinct (map :y (:h edges))))]
    (vec
     (for [[top bottom] (partition 2 1 ys)
           [x0 x1] (partition 2 1 xs)
           :when (and (intersection? edges x0 top x-tolerance y-tolerance)
                      (intersection? edges x1 top x-tolerance y-tolerance)
                      (intersection? edges x0 bottom x-tolerance y-tolerance)
                      (intersection? edges x1 bottom x-tolerance y-tolerance))]
       [x0 top x1 bottom]))))

(defn- adjacent-cells? [[ax0 at ax1 ab] [bx0 bt bx1 bb] tol]
  (or (and (<= (Math/abs (- (double at) bt)) tol)
           (<= (Math/abs (- (double ab) bb)) tol)
           (or (<= (Math/abs (- (double ax1) bx0)) tol)
               (<= (Math/abs (- (double bx1) ax0)) tol)))
      (and (<= (Math/abs (- (double ax0) bx0)) tol)
           (<= (Math/abs (- (double ax1) bx1)) tol)
           (or (<= (Math/abs (- (double ab) bt)) tol)
               (<= (Math/abs (- (double bb) at)) tol)))))

(defn- cell-components [cells tolerance]
  (loop [remaining (set cells) components []]
    (if-let [seed (first remaining)]
      (let [component
            (loop [found #{seed} frontier [seed]]
              (if-let [cell (peek frontier)]
                (let [neighbors (filter #(and (not (contains? found %))
                                              (adjacent-cells? cell % tolerance))
                                        remaining)]
                  (recur (into found neighbors)
                         (into (pop frontier) neighbors)))
                found))]
        (recur (reduce disj remaining component) (conj components component)))
      components)))

(defn- cell-text [words bbox]
  (->> words
       (filter #(g/within? bbox (g/center [(:x0 %) (:top %) (:x1 %) (:bottom %)])))
       (sort-by (juxt :top :x0))
       (map :text)
       (str/join " ")))

(defn- assemble-rows [cells words tolerance]
  (->> cells
       (group-by (fn [[_ top]] (Math/round (/ (double top) tolerance))))
       (sort-by key)
       (mapv (fn [[_ row]]
               (mapv (fn [bbox] {:text (cell-text words bbox) :bbox bbox})
                     (sort-by first row))))))

(defn- component-bbox [cells]
  [(reduce min (map first cells))
   (reduce min (map second cells))
   (reduce max (map #(nth % 2) cells))
   (reduce max (map #(nth % 3) cells))])

(defn- table-strategy [{:keys [vertical-strategy horizontal-strategy]}]
  (if (= vertical-strategy horizontal-strategy)
    vertical-strategy
    {:vertical vertical-strategy :horizontal horizontal-strategy}))

(defn- detected-tables [doc opts]
  (let [opts (merge defaults opts)
        text-opts (into {}
                        (keep (fn [[k v]]
                                (let [n (name k)]
                                  (when (str/starts-with? n "text-")
                                    [(keyword (subs n 5)) v]))))
                        opts)
        words (text/words doc (merge opts text-opts))
        objs (objects/objects doc opts)
        edges (strategy-edges doc words objs opts)
        x-tolerance (double (or (:intersection-x-tolerance opts)
                                (:intersection-tolerance opts)))
        y-tolerance (double (or (:intersection-y-tolerance opts)
                                (:intersection-tolerance opts)))
        tolerance (max x-tolerance y-tolerance)
        cells (grid-cells edges x-tolerance y-tolerance)]
    (->> (cell-components cells tolerance)
         (map (fn [component]
                (let [cells (vec (sort-by (juxt second first) component))
                      bbox (component-bbox cells)
                      region-h (filter #(and (<= (:x0 %) (nth bbox 2))
                                             (>= (:x1 %) (first bbox))) (:h edges))
                      region-v (filter #(and (<= (:top %) (nth bbox 3))
                                             (>= (:bottom %) (second bbox))) (:v edges))]
                  {:page-number (or (:page opts) (:page-number (first words)) 1)
                   :strategy (table-strategy opts)
                   :bbox bbox
                   :rows (assemble-rows cells words
                                        (max 0.001 (double (or (:snap-y-tolerance opts)
                                                               (:snap-tolerance opts)))))
                   :cells cells
                   :debug (cond-> {:horizontal-lines (count region-h)
                                   :vertical-lines (count region-v)
                                   :cells (count cells)}
                            (= :text (:vertical-strategy opts))
                            (assoc :columns (dec (count (distinct (map :x region-v)))))
                            (= :text (:horizontal-strategy opts))
                            (assoc :rows (dec (count (distinct (map :y region-h))))))})))
         (sort-by (juxt #(second (:bbox %)) #(first (:bbox %))))
         vec)))

(defn- normalize-options [opts]
  (let [legacy (:strategy opts)
        opts (cond-> opts
               (and legacy (not (contains? opts :vertical-strategy)))
               (assoc :vertical-strategy legacy)
               (and legacy (not (contains? opts :horizontal-strategy)))
               (assoc :horizontal-strategy legacy))
        opts (merge defaults opts)]
    (doseq [[axis strategy] [[:vertical (:vertical-strategy opts)]
                             [:horizontal (:horizontal-strategy opts)]]]
      (when-not (contains? strategies strategy)
        (throw (ex-info (str "Unknown " (name axis) " table strategy: " strategy)
                        {:pdfplumber/error :unknown-strategy
                         :axis axis :strategy strategy}))))
    opts))

(defn extract-tables
  "Detect and extract every independent table on a page, ordered top-to-bottom
   then left-to-right. Unlike versions through 0.1.2, this returns one table per
   connected cell region instead of at most one page-wide bounding grid.

   Options include `:vertical-strategy` and `:horizontal-strategy`, each one of
   `:lines`, `:lines-strict`, `:text`, or `:explicit`; corresponding
   `:explicit-vertical-lines` / `:explicit-horizontal-lines`; and
   `:snap-tolerance`, `:join-tolerance`, `:edge-min-length`,
   `:intersection-tolerance`, `:min-words-vertical`, and
   `:min-words-horizontal`. The legacy `:strategy` option sets both axes."
  ([doc] (extract-tables doc {}))
  ([doc opts]
   (detected-tables doc (normalize-options opts))))

(defn extract-table
  "Extract the first detected table, ordered top-to-bottom then left-to-right,
   or nil when no table is found. This preserves the singular API while
   `extract-tables` returns all independent table regions. Options are the same
   as `extract-tables`."
  ([doc] (extract-table doc {}))
  ([doc opts]
   (first (extract-tables doc opts))))

(defn- table-columns [table]
  (let [[_ top _ bottom] (:bbox table)]
    (->> (:cells table)
         (group-by (fn [[x0 _ x1 _]] [x0 x1]))
         (sort-by (comp first key))
         (mapv (fn [[[x0 x1] cells]]
                 {:bbox [x0 top x1 bottom] :cells (vec cells)})))))

(defn- as-table [table]
  (let [extracted (mapv (fn [row] (mapv :text row)) (:rows table))]
    (assoc table
           :columns (table-columns table)
           :extract (fn [] extracted))))

(defn find-tables
  "Find Table maps with `:rows`, `:columns`, `:cells`, `:bbox`, and a zero-arg
   `:extract` function."
  ([doc] (find-tables doc {}))
  ([doc opts] (mapv as-table (extract-tables doc opts))))

(defn find-table
  "First Table map from `find-tables`, or nil."
  ([doc] (find-table doc {}))
  ([doc opts] (first (find-tables doc opts))))
