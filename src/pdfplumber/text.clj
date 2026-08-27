(ns pdfplumber.text
  "Extract characters, words, and text with PDFBox's PDFTextStripper.

   PDFTextStripper reports direction-adjusted coordinates in a top-left origin.
   Char maps normalize page and content rotations before word grouping. Words
   are formed by clustering chars into lines (within `:y-tolerance`) and
   splitting on horizontal gaps wider than `:x-tolerance`."
  (:refer-clojure :exclude [chars])
  (:require [clojure.string :as str]
            [pdfplumber.geometry :as g]
            [pdfplumber.page :as page])
  (:import [org.apache.pdfbox.cos COSDictionary COSName]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.text PDFTextStripper TextPosition]
           [org.apache.pdfbox.util Matrix]
           [java.util List]
           [java.util.regex Pattern Matcher]))

(set! *warn-on-reflection* true)

(def ^:private default-tolerance 3.0)
(def ^:private default-line-dir :ttb)
(def ^:private default-char-dir :ltr)

(defn- matrix-values [^Matrix matrix]
  [(double (.getScaleX matrix))
   (double (.getShearY matrix))
   (double (.getShearX matrix))
   (double (.getScaleY matrix))
   (double (.getTranslateX matrix))
   (double (.getTranslateY matrix))])

(defn- content-bbox [^org.apache.pdfbox.pdmodel.font.PDFont font
                    page-height [scale-x shear-y shear-x scale-y translate-x translate-y]
                    glyph-advance]
  (let [descriptor (.getFontDescriptor font)
        glyph-scale (max (Math/abs (double scale-x)) (Math/abs (double shear-y))
                         (Math/abs (double shear-x)) (Math/abs (double scale-y)))
        descent (if descriptor
                  (* (double (.getDescent descriptor)) 0.001)
                  0.0)
        font-em 1.0
        advance-space (/ glyph-advance glyph-scale)
        y0 descent
        y1 (+ descent font-em)
        points [[0.0 y0] [advance-space y0]
                [0.0 y1] [advance-space y1]]
        xs (map (fn [[x y]] (+ (* scale-x x) (* shear-x y) translate-x)) points)
        ys (map (fn [[x y]] (+ (* shear-y x) (* scale-y y) translate-y)) points)
        min-x (reduce min xs)
        max-x (reduce max xs)
        min-y (reduce min ys)
        max-y (reduce max ys)]
    [min-x (- page-height max-y) max-x (- page-height min-y)]))

(defn- text-for-position [^TextPosition tp]
  (let [unicode (.getUnicode tp)
        codes (.getCharacterCodes tp)
        mapped (some #(.toUnicode ^org.apache.pdfbox.pdmodel.font.PDFont
                                  (.getFont tp) (int %))
                     codes)]
    (or mapped
        (when (seq unicode) unicode)
        (when-let [code (first codes)]
          (str "(cid:" code ")"))
        "")))

(defn- tp->char [^TextPosition tp page-no page-width page-height rotation doctop-offset]
  (let [text (text-for-position tp)
        [scale-x shear-y shear-x scale-y translate-x translate-y :as matrix]
        (matrix-values (.getTextMatrix tp))
        raw-x0 (double (.getXDirAdj tp))
        w (double (.getWidthDirAdj tp))
        h (double (.getHeightDir tp))
        content-advance (if (pos? w)
                          w
                          (Math/abs (- (double (.getEndY tp)) translate-y)))
        raw-bottom (double (.getYDirAdj tp))
        raw-top (- raw-bottom h)
        [raw-x0 raw-top raw-x1 raw-bottom]
        (cond
          (and (< scale-x 0.0) (< scale-y 0.0))
          [(- page-width raw-x0 w) (- page-height raw-bottom)
           (- page-width raw-x0) (- page-height raw-top)]
          (< scale-x 0.0)
          [(- raw-x0 w) raw-top raw-x0 raw-bottom]
          :else
          [raw-x0 raw-top (+ raw-x0 w) raw-bottom])
        content-horizontal? (>= (Math/abs (double scale-x))
                                (Math/abs (double shear-y)))
        determinant (- (* scale-x scale-y) (* shear-y shear-x))
        apply-page-rotation? (and content-horizontal?
                               (contains? #{180 270} rotation))
        apply-content-rotation? (and (zero? rotation)
                                     (or (not content-horizontal?)
                                         (neg? determinant)))
        [x0 top x1 bottom] (cond
                             apply-page-rotation?
                             (g/rotate-bbox [raw-x0 raw-top raw-x1 raw-bottom]
                                            page-width page-height rotation)

                             apply-content-rotation?
                             (content-bbox (.getFont tp) page-height matrix content-advance)

                             :else
                             [raw-x0 raw-top raw-x1 raw-bottom])
        upright (and (pos? determinant)
                             (if (contains? #{90 270} rotation)
                       (not content-horizontal?)
                       content-horizontal?))
        fontname (some-> (.getFont tp) .getName)
        size (double (.getFontSizeInPt tp))]
    {:text text
     :x0 x0
     :top top
     :x1 x1
     :bottom bottom
     :y0 (- page-height bottom)
     :y1 (- page-height top)
     :width (- x1 x0)
     :height (- bottom top)
     :doctop (+ doctop-offset top)
     :fontname fontname
     :size size
     :adv w
     :upright upright
     :page-rotation rotation
     :content-horizontal content-horizontal?
     :matrix matrix
     :object-type :char
     ;; Legacy spellings are supported.
     :font-name fontname
     :font-size size
     :page-number page-no}))

(defn- char-bbox [c]
  [(:x0 c) (:top c) (:x1 c) (:bottom c)])

(defn- collecting-stripper
  "A PDFTextStripper that adds each char map, tagged with `page-no`, to `acc`."
  (^PDFTextStripper [acc page-no page-width page-height rotation doctop-offset use-text-flow]
   (if use-text-flow
     (proxy [PDFTextStripper] []
       (processTextPosition [^TextPosition tp]
         (swap! acc conj (tp->char tp page-no page-width page-height rotation doctop-offset))))
     (proxy [PDFTextStripper] []
       (writeString [^String _text ^List text-positions]
         (doseq [^TextPosition tp text-positions]
           (swap! acc conj (tp->char tp page-no page-width page-height rotation doctop-offset)))))))
  (^PDFTextStripper [acc page-no page-width page-height rotation doctop-offset
                     use-text-flow mcid-state]
   (let [add-char (fn [^TextPosition tp]
                    (let [char (tp->char tp page-no page-width page-height rotation doctop-offset)]
                      (swap! acc conj (if mcid-state
                                        (assoc char :mcid (peek @mcid-state))
                                        char))))]
     (if use-text-flow
       (proxy [PDFTextStripper] []
         (beginMarkedContentSequence [^COSName _tag ^COSDictionary properties]
           (swap! mcid-state conj (when properties
                                    (let [mcid (.getInt properties COSName/MCID -1)]
                                      (when (not= -1 mcid) mcid)))))
         (endMarkedContentSequence [] (swap! mcid-state pop))
         (processTextPosition [^TextPosition tp] (add-char tp)))
       (proxy [PDFTextStripper] []
         (beginMarkedContentSequence [^COSName _tag ^COSDictionary properties]
           (swap! mcid-state conj (when properties
                                    (let [mcid (.getInt properties COSName/MCID -1)]
                                      (when (not= -1 mcid) mcid)))))
         (endMarkedContentSequence [] (swap! mcid-state pop))
         (writeString [^String _text ^List text-positions]
           (doseq [^TextPosition tp text-positions]
             (add-char tp))))))))

(defn- page-height [^PDDocument doc ^long p]
  (double (.getHeight (.getMediaBox (.getPage doc (dec (int p)))))))

(defn- doctop-offset [^PDDocument doc ^long p]
  (reduce + 0.0 (map #(page-height doc %) (range 1 p))))

(defn- page-chars [^PDDocument doc ^long p use-text-flow include-mcid?]
  (let [acc (atom [])
        mcid-state (when include-mcid? (atom []))
        page (.getPage doc (dec (int p)))
        box (.getMediaBox page)
        width (double (.getWidth box))
        height (page-height doc p)
        rotation (mod (.getRotation page) 360)
        ^PDFTextStripper stripper (if include-mcid?
                                  (collecting-stripper acc p width height rotation
                                                       (doctop-offset doc p)
                                                       use-text-flow
                                                       mcid-state)
                                  (collecting-stripper acc p width height rotation
                                                       (doctop-offset doc p)
                                                       use-text-flow))]
    (.setSortByPosition stripper false)
    (.setSuppressDuplicateOverlappingText stripper false)
    (.setStartPage stripper (int p))
    (.setEndPage stripper (int p))
    (.getText stripper doc)
    (let [chars (mapv (fn [[source-order c]]
                        (with-meta c (assoc (meta c) :source-order source-order)))
                      (map-indexed vector @acc))]
      (if (or use-text-flow
              (some #(not (:upright %)) chars))
        chars
        (vec (sort-by (juxt :top :x0) chars))))))

(def ^:private option-aliases
  {:keep_blank_chars :keep-blank-chars
   :use_text_flow :use-text-flow
   :horizontal_ltr :horizontal-ltr
   :line_dir :line-dir
   :char_dir :char-dir
   :line_dir_rotated :line-dir-rotated
   :char_dir_rotated :char-dir-rotated
   :extra_attrs :extra-attrs
   :split_at_punctuation :split-at-punctuation
   :expand_ligatures :expand-ligatures})

(defn- normalize-options [opts]
  (reduce-kv (fn [m old new]
               (if (and (contains? m old) (not (contains? m new)))
                 (assoc m new (get m old))
                 m))
             opts option-aliases))

(defn chars
  "Vector of character maps `{:text :x0 :top :x1 :bottom :font-name :font-size
   :page-number}`. Options: `:page` (1-based, limit to one page) and `:bbox`
   (keep chars whose center falls inside `[x0 top x1 bottom]`)."
  ([doc] (chars doc {}))
  ([^PDDocument doc opts]
   (let [{:keys [page bbox use-text-flow view-operations include-mcid?]} (normalize-options opts)
         pages (if page [(long page)] (range 1 (inc (.getNumberOfPages doc))))
         cs (into [] (mapcat #(page-chars doc % use-text-flow include-mcid?)) pages)]
     (cond-> (if (and bbox (not view-operations))
               (filterv #(g/within? bbox (g/center (char-bbox %))) cs)
               cs)
       view-operations (page/apply-view view-operations)))))

(defn- whitespace? [s]
  (or (nil? s)
      (str/blank? s)
      (and (string? s)
           (every? (fn [c]
                     (or (Character/isWhitespace ^char c)
                         (Character/isSpaceChar ^char c)))
                   s))))

(defn- direction-value [opts key default]
  (or (get opts key) default))

(defn- directions [opts item]
  (let [horizontal-ltr (not= false (:horizontal-ltr opts))
        upright (:upright item)
        char-dir (direction-value opts :char-dir (if horizontal-ltr :ltr :rtl))
        line-dir (direction-value opts :line-dir default-line-dir)
        page-rotated-horizontal-content?
        (and (not upright)
             (= 90 (:page-rotation item))
             (:content-horizontal item))]
    (if page-rotated-horizontal-content?
      [line-dir (if horizontal-ltr :ltr :rtl)]
      [(if upright line-dir (direction-value opts :line-dir-rotated char-dir))
       (if upright char-dir (direction-value opts :char-dir-rotated line-dir))])))

(defn- direction-coordinate [item direction]
  (let [item (if (map? item) item (first item))]
    (case direction
      :ttb (double (:top item))
      :btt (- (double (:bottom item)))
      :ltr (double (:x0 item))
      :rtl (- (double (:x1 item))))))

(defn- direction-sort-key [item direction]
  (let [item (if (map? item) item (first item))
        nonblank (if (whitespace? (:text item)) 1 0)]
    (case direction
      :ttb [(double (:top item)) nonblank (double (:bottom item))]
      :btt [(- (+ (double (:top item)) (double (:height item))))
            nonblank
            (- (double (:top item)))]
      :ltr [(double (:x0 item))
            (or (:source-order (meta item)) nonblank)]
      :rtl [(- (double (:x1 item)))
            (or (:source-order (meta item)) nonblank)])))

(defn- cluster-items [items direction tolerance]
  (let [coordinates (->> items
                         (map #(direction-coordinate % direction))
                         distinct
                         sort)
        coordinate-groups (reduce (fn [groups coordinate]
                                    (if (and (seq groups)
                                             (<= (- coordinate (last (peek groups)))
                                                 tolerance))
                                      (conj (pop groups) (conj (peek groups) coordinate))
                                      (conj groups [coordinate])))
                                  [] coordinates)
        group-ids (into {}
                        (mapcat (fn [[group-id group]]
                                  (map #(vector % group-id) group))
                                (map-indexed vector coordinate-groups)))]
    (->> items
         (group-by #(get group-ids (direction-coordinate % direction)))
         (sort-by key)
         (mapv val))))

(defn- cluster-lines
  "Group chars into lines using the configured line direction."
  [chars opts]
  (let [[line-dir _] (directions opts (first chars))]
    (if (and (= :ttb line-dir) (:upright (first chars)))
      (reduce (fn [lines c]
                (let [line (peek lines)
                      line-key (if (:cluster-by-top opts) :top :y0)
                      line-baseline (when line
                                      (line-key ((if (:cluster-transitively opts)
                                                   last
                                                   first)
                                                  line)))]
                  (if (and line-baseline
                           (<= (Math/abs (- (double (get c line-key))
                                            (double line-baseline)))
                               (:y-tolerance opts)))
                    (conj (pop lines) (conj line c))
                    (conj lines [c]))))
              []
              (if (:use-text-flow opts)
                chars
                (sort-by :top chars)))
      (cluster-items chars line-dir
                     (if (contains? #{:ltr :rtl} line-dir)
                       (:x-tolerance opts)
                       (:y-tolerance opts))))))

(def ^:private ligatures
  {"ﬀ" "ff" "ﬁ" "fi" "ﬂ" "fl" "ﬃ" "ffi" "ﬄ" "ffl" "ﬅ" "ft" "ﬆ" "st"})

(defn- output-text [s expand-ligatures]
  (if expand-ligatures (get ligatures s s) s))

(defn- merge-word [cs extra-attrs expand-ligatures]
  (merge {:text (apply str (map #(output-text (:text %) expand-ligatures) cs))
   :x0 (reduce min (map :x0 cs))
   :top (reduce min (map :top cs))
   :x1 (reduce max (map :x1 cs))
   :bottom (reduce max (map :bottom cs))
   :page-number (:page-number (first cs))}
         (select-keys (first cs) extra-attrs)))

(defn- punctuation? [split-at-punctuation s]
  (when split-at-punctuation
    (let [punctuation (if (string? split-at-punctuation)
                        (set split-at-punctuation)
                        (set "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"))]
      (and (= 1 (count s)) (contains? punctuation (first s))))))

(defn- attrs-changed? [extra-attrs prior c]
  (and prior (some #(not= (get prior %) (get c %)) extra-attrs)))

(defn- line-word-groups
  "Split a line's chars into words. The chars are sorted left-to-right and
   retain whitespace.
   A whitespace char or a gap wider than `x-tol` starts a new word."
  [line opts]
  (let [{:keys [x-tolerance keep-blank-chars extra-attrs
                split-at-punctuation use-text-flow]} opts
        [_ char-dir] (directions opts (first line))
        ordered (if use-text-flow line
                    (sort-by #(direction-sort-key % char-dir) line))]
   (loop [cs ordered, cur [], words []]
    (if-let [c (first cs)]
      (cond
        (and (whitespace? (:text c)) (not keep-blank-chars))
        (recur (rest cs) [] (cond-> words (seq cur) (conj cur)))

        (and (seq cur)
             (or (attrs-changed? extra-attrs (peek cur) c)
                 (let [prior (peek cur)
                       [intra-tolerance interline-tolerance]
                       (if (contains? #{:ttb :btt} char-dir)
                         [(:y-tolerance opts) (:x-tolerance opts)]
                         [(:x-tolerance opts) (:y-tolerance opts)])
                       gap (case char-dir
                             :ltr (- (double (:x0 c)) (double (:x1 prior)))
                             :rtl (- (double (:x0 prior)) (double (:x1 c)))
                             :ttb (- (double (:top c)) (double (:bottom prior)))
                             :btt (- (double (:top prior)) (double (:bottom c))))
                       orthogonal-coordinate (if (contains? #{:ttb :btt} char-dir)
                                               :x0
                                               (if (:cluster-by-top opts) :top :y0))
                       orthogonal-gap (Math/abs
                                       (- (double (get c orthogonal-coordinate))
                                          (double (get prior orthogonal-coordinate))))]
                   (or (> (if use-text-flow (Math/abs gap) gap) intra-tolerance)
                       (> orthogonal-gap interline-tolerance)))))
        (recur (rest cs) [c] (conj words cur))

        (punctuation? split-at-punctuation (:text c))
        (recur (rest cs) [] (cond-> words
                              (seq cur) (conj cur)
                              true (conj [c])))

        :else
        (recur (rest cs) (conj cur c) words))
      (cond-> words (seq cur) (conj cur))))))

(defn- word-data-from-chars [cs opts]
  (let [opts (merge {:x-tolerance default-tolerance
                     :y-tolerance default-tolerance
                     :horizontal-ltr true
                     :extra-attrs []
                     :expand-ligatures true}
                    (normalize-options opts))
        opts (assoc opts :cluster-by-top
                    (if (contains? opts :cluster-by-top)
                      (:cluster-by-top opts)
                      (some #(not (:upright %)) cs)))
        lines (->> cs
                   (partition-by #(select-keys % (into [:upright] (:extra-attrs opts))))
                   (mapcat #(cluster-lines % opts))
                   vec)
        groups (mapv #(line-word-groups % opts) lines)]
    {:opts opts :lines lines :groups groups
     :words (mapv (fn [line-groups]
                    (mapv #(merge-word % (:extra-attrs opts)
                                       (:expand-ligatures opts))
                          line-groups))
                  groups)}))

(defn- word-data [doc opts]
  (word-data-from-chars (chars doc opts) opts))

(defn words
  "Vector of word maps `{:text :x0 :top :x1 :bottom :page-number}` in reading order.
   Options: `:page`, `:bbox`, `:x-tolerance` (default 3.0), `:y-tolerance`
   (default 3.0)."
  ([doc] (words doc {}))
  ([doc opts]
   (into [] cat (:words (word-data doc opts)))))

(defn extract-words
  "Pdfplumber-compatible entry point for `words`."
  ([doc] (words doc {}))
  ([doc opts] (words doc opts)))

(defn word-map
  "Word-to-source-character mapping. Returns `:words` and a parallel
   `:word-chars` vector, preserving the TextPosition-derived char maps."
  ([doc] (word-map doc {}))
  ([doc opts]
   (let [{:keys [words groups]} (word-data doc opts)]
     {:words (into [] cat words)
      :word-chars (into [] cat groups)})))

(defn- mapped-word-tuples [word-chars expand-ligatures]
  (mapcat (fn [c]
            (map (fn [out-char] [c (str out-char)])
                 (output-text (:text c) expand-ligatures)))
          word-chars))

(defn text-map
  "Text output and `[source-char output-character]` tuples. Inserted spaces and
   newlines carry a nil source char."
  ([doc] (text-map doc {}))
  ([doc opts]
   (let [{:keys [groups opts]} (word-data doc opts)
         tuples (vec
                 (mapcat (fn [line-index line]
                           (concat
                            (when (pos? line-index) [[nil "\n"]])
                            (mapcat (fn [word-index word-chars]
                                      (concat
                                       (when (pos? word-index) [[nil " "]])
                                       (mapped-word-tuples word-chars
                                                           (:expand-ligatures opts))))
                                    (range) line)))
                         (range) groups))]
     {:text (apply str (map second tuples)) :tuples tuples})))

(defn text-from-chars
  "Reconstruct text from character maps."
  ([char-records] (text-from-chars char-records {}))
  ([char-records opts]
   (let [{:keys [words groups opts]} (word-data-from-chars char-records opts)
         pairs (vec (mapcat (fn [word-line char-line]
                              (map vector word-line char-line))
                            words groups))
         line-dir (direction-value opts :line-dir default-line-dir)
         line-tolerance (if (contains? #{:ltr :rtl} line-dir)
                          (:x-tolerance opts)
                          (:y-tolerance opts))
         render-lines (cluster-items pairs line-dir line-tolerance)]
     (str/join "\n"
               (map (fn [line]
                      (str/join " " (map (comp :text first) line)))
                    render-lines)))))
(defn- layout-text [doc opts]
  (let [{:keys [words]} (word-data doc opts)
        density (double (or (:x-density opts) 7.25))]
    (->> words
         (map (fn [line]
                (loop [remaining line prior nil out ""]
                  (if-let [word (first remaining)]
                    (let [spaces (if prior
                                   (max 1 (long (Math/round
                                                 (/ (- (:x0 word) (:x1 prior)) density))))
                                   (max 0 (long (Math/round (/ (:x0 word) density)))))]
                      (recur (rest remaining) word
                             (str out (apply str (repeat spaces " ")) (:text word))))
                    out))))
         (str/join "\n"))))

(defn- chars-bounds [cs]
  (when (seq cs)
    {:x0 (reduce min (map :x0 cs))
     :top (reduce min (map :top cs))
     :x1 (reduce max (map :x1 cs))
     :bottom (reduce max (map :bottom cs))
     :y0 (reduce min (map :y0 cs))
     :y1 (reduce max (map :y1 cs))
     :doctop (reduce min (map :doctop cs))}))

(defn extract-text-lines
  "Extract positional line maps. Each includes text, bounds, page number, and
   contributing chars unless `:return-chars false` is supplied."
  ([doc] (extract-text-lines doc {}))
  ([doc opts]
   (let [{:keys [words groups]} (word-data doc opts)
         return-chars (not= false (or (:return-chars opts) (:return_chars opts)))]
     (mapv (fn [line-words line-groups]
             (let [cs (vec (mapcat identity line-groups))]
               (cond-> (merge {:text (str/join " " (map :text line-words))
                               :object-type :text-line
                               :page-number (:page-number (first cs))}
                              (chars-bounds cs))
                 return-chars (assoc :chars cs))))
           words groups))))

(defn- search-pattern [pattern regex? case-sensitive?]
  (if (instance? Pattern pattern)
    pattern
    (Pattern/compile (if regex? (str pattern) (Pattern/quote (str pattern)))
                     (if case-sensitive? 0 Pattern/CASE_INSENSITIVE))))

(defn- distinct-chars [tuples]
  (reduce (fn [out [c _]]
            (if (or (nil? c) (= c (peek out))) out (conj out c)))
          [] tuples))

(defn search
  "Search reconstructed text with a regex Pattern or string. Results include
   match text, capture groups, bounds, and contributing chars. String patterns
   are regexes by default; set `:regex false` for literal matching."
  ([doc pattern] (search doc pattern {}))
  ([doc pattern opts]
   (let [{:keys [text tuples]} (text-map doc opts)
         regex? (not= false (:regex opts))
         case-sensitive? (not= false (or (:case-sensitive opts) (:case opts)))
         ^Matcher matcher (.matcher ^Pattern (search-pattern pattern regex?
                                                              case-sensitive?)
                                   ^CharSequence text)]
     (loop [matches []]
       (if (.find matcher)
         (let [matched (.group matcher)
               contributing (distinct-chars (subvec tuples (.start matcher) (.end matcher)))
               groups (mapv #(.group matcher (int %))
                            (range 1 (inc (.groupCount matcher))))]
           (recur (cond-> matches
                    (and (seq matched) (not (str/blank? matched)) (seq contributing))
                    (conj (merge {:text matched
                                  :groups groups
                                  :chars contributing
                                  :page-number (:page-number (first contributing))}
                                 (chars-bounds contributing))))))
         matches)))))

(defn dedupe-char-records
  "Remove duplicate chars within positional `:tolerance`."
  ([char-records] (dedupe-char-records char-records {}))
  ([char-records opts]
   (let [opts (normalize-options opts)
         tolerance (double (or (:tolerance opts) 1.0))
         extra-attrs (cond
                       (contains? opts :extra-attrs) (:extra-attrs opts)
                       (contains? opts :compare-attrs) (:compare-attrs opts)
                       :else [:fontname :size])
         attrs (vec (distinct (concat [:upright :text] extra-attrs)))
         cluster (fn [records attr]
                   (reduce (fn [groups record]
                             (if (and (seq groups)
                                      (<= (- (double (get record attr))
                                             (double (get (peek (peek groups)) attr)))
                                          tolerance))
                               (conj (pop groups) (conj (peek groups) record))
                               (conj groups [record])))
                           []
                           (sort-by attr records)))
         indexed (map-indexed vector char-records)
         groups (vals (group-by (fn [[_ record]] (mapv #(get record %) attrs)) indexed))
         deduped (mapcat (fn [group]
                           (mapcat #(cluster % :x0)
                                   (cluster (map second group) :doctop)))
                         groups)]
     (->> deduped
          (map #(first (sort-by (juxt :doctop :x0) %)))
          (sort-by #(.indexOf ^java.util.List (vec char-records) %))
          vec))))

(defn dedupe-chars
  "Extract chars and remove positional duplicates. Extraction and comparison
   options share the same map."
  ([doc] (dedupe-chars doc {}))
  ([doc opts] (dedupe-char-records (chars doc opts) opts)))

(defn- simple-lines [char-records y-tolerance]
  (reduce (fn [lines c]
            (let [line (peek lines)
                  line-top (some-> line first :doctop)]
              (if (and line-top
                       (<= (Math/abs (- (double (:doctop c))
                                        (double line-top)))
                           y-tolerance))
                (conj (pop lines) (conj line c))
                (conj lines [c]))))
          [] (sort-by :doctop char-records)))

(defn- collate-simple-line [line x-tolerance]
  (loop [remaining (sort-by :x0 line) prior nil out ""]
    (if-let [c (first remaining)]
      (let [gap? (and prior
                      (> (- (double (:x0 c)) (double (:x1 prior))) x-tolerance))]
        (recur (rest remaining) c
               (str out (when gap? " ") (:text c))))
      out)))

(defn extract-text-simple
  "Reconstruct text quickly with doctop line clusters and direct character-gap
   spacing. It does not build word or text maps."
  ([doc] (extract-text-simple doc {}))
  ([doc {:keys [x-tolerance y-tolerance]
         :or {x-tolerance default-tolerance y-tolerance default-tolerance}
         :as opts}]
   (->> (simple-lines (chars doc opts) y-tolerance)
        (map #(collate-simple-line % x-tolerance))
        (str/join "\n"))))

(defn text
  "Reconstructed text: words join with spaces in a line. Lines join with newlines.
   Accepts the same options as `words`."
  ([doc] (text doc {}))
  ([doc opts]
   (if (:layout opts)
     (layout-text doc opts)
     (:text (text-map doc opts)))))

(defn extract-text
  "Pdfplumber-compatible text entry point, including `:layout`,
   `:keep-blank-chars`, and `:use-text-flow` options."
  ([doc] (text doc {}))
  ([doc opts] (text doc opts)))
