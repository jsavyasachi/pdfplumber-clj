(ns pdfplumber.text
  "Character, word, and text extraction over PDFBox's PDFTextStripper.

   PDFTextStripper already reports direction-adjusted coordinates in a top-left
   origin, so char maps are built directly from `getXDirAdj`/`getYDirAdj` without
   a page-height flip. Words are formed by clustering chars into lines (within
   `:y-tolerance`) and splitting on horizontal gaps wider than `:x-tolerance`."
  (:refer-clojure :exclude [chars])
  (:require [clojure.string :as str]
            [pdfplumber.geometry :as g]
            [pdfplumber.page :as page])
  (:import [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.text PDFTextStripper TextPosition]
           [org.apache.pdfbox.util Matrix]
           [java.util List]
           [java.util.regex Pattern Matcher]))

(set! *warn-on-reflection* true)

(def ^:private default-tolerance 3.0)

(defn- matrix-values [^Matrix matrix]
  [(double (.getScaleX matrix))
   (double (.getShearY matrix))
   (double (.getShearX matrix))
   (double (.getScaleY matrix))
   (double (.getTranslateX matrix))
   (double (.getTranslateY matrix))])

(defn- tp->char [^TextPosition tp ^long page-no page-height doctop-offset]
  (let [x0 (double (.getXDirAdj tp))
        w (double (.getWidthDirAdj tp))
        h (double (.getHeightDir tp))
        bottom (double (.getYDirAdj tp))
        top (- bottom h)
        fontname (some-> (.getFont tp) .getName)
        size (double (.getFontSizeInPt tp))]
    {:text (.getUnicode tp)
     :x0 x0
     :top top
     :x1 (+ x0 w)
     :bottom bottom
     :y0 (- page-height bottom)
     :y1 (- page-height top)
     :width w
     :height h
     :doctop (+ doctop-offset top)
     :fontname fontname
     :size size
     :adv w
     :upright (zero? (mod (double (.getDir tp)) 360.0))
     :matrix (matrix-values (.getTextMatrix tp))
     :object-type :char
     ;; Legacy spellings remain supported.
     :font-name fontname
     :font-size size
     :page-number page-no}))

(defn- char-bbox [c]
  [(:x0 c) (:top c) (:x1 c) (:bottom c)])

(defn- collecting-stripper
  "A PDFTextStripper that appends each char map (tagged with `page-no`) to `acc`."
  ^PDFTextStripper [acc page-no page-height doctop-offset use-text-flow]
  (if use-text-flow
    (proxy [PDFTextStripper] []
      (processTextPosition [^TextPosition tp]
        (swap! acc conj (tp->char tp page-no page-height doctop-offset))))
    (proxy [PDFTextStripper] []
      (writeString [^String _text ^List text-positions]
        (doseq [^TextPosition tp text-positions]
          (swap! acc conj (tp->char tp page-no page-height doctop-offset)))))))

(defn- page-height [^PDDocument doc ^long p]
  (double (.getHeight (.getMediaBox (.getPage doc (dec (int p)))))))

(defn- doctop-offset [^PDDocument doc ^long p]
  (reduce + 0.0 (map #(page-height doc %) (range 1 p))))

(defn- page-chars [^PDDocument doc ^long p use-text-flow]
  (let [acc (atom [])
        height (page-height doc p)
        ^PDFTextStripper stripper (collecting-stripper acc p height
                                                       (doctop-offset doc p)
                                                       use-text-flow)]
    (.setSortByPosition stripper (not use-text-flow))
    (.setStartPage stripper (int p))
    (.setEndPage stripper (int p))
    (.getText stripper doc)
    @acc))

(def ^:private option-aliases
  {:keep_blank_chars :keep-blank-chars
   :use_text_flow :use-text-flow
   :horizontal_ltr :horizontal-ltr
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
   (let [{:keys [page bbox use-text-flow view-operations]} (normalize-options opts)
         pages (if page [(long page)] (range 1 (inc (.getNumberOfPages doc))))
         cs (into [] (mapcat #(page-chars doc % use-text-flow)) pages)]
     (cond-> (if (and bbox (not view-operations))
               (filterv #(g/within? bbox (g/center (char-bbox %))) cs)
               cs)
       view-operations (page/apply-view view-operations)))))

(defn- whitespace? [s]
  (or (nil? s) (str/blank? s)))

(defn- cluster-lines
  "Group chars into lines, top-to-bottom, by `:top` within `y-tol`."
  [chars y-tol use-text-flow]
  (reduce (fn [acc c]
            (let [line (peek acc)
                  ltop (some-> line first :top)]
              (if (and ltop (<= (Math/abs (- (double (:top c)) (double ltop))) y-tol))
                (conj (pop acc) (conj line c))
                (conj acc [c]))))
          []
          (if use-text-flow chars (sort-by :top chars))))

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
  "Split a line's chars (sorted left-to-right, whitespace retained) into words.
   A whitespace char or a gap wider than `x-tol` starts a new word."
  [line {:keys [x-tolerance keep-blank-chars horizontal-ltr extra-attrs
                split-at-punctuation use-text-flow]
         :or {horizontal-ltr true extra-attrs []}}]
  (let [ordered (if use-text-flow line (sort-by :x0 line))
        ordered (if horizontal-ltr ordered (reverse ordered))]
   (loop [cs ordered, cur [], words []]
    (if-let [c (first cs)]
      (cond
        (and (whitespace? (:text c)) (not keep-blank-chars))
        (recur (rest cs) [] (cond-> words (seq cur) (conj cur)))

        (and (seq cur)
             (or (attrs-changed? extra-attrs (peek cur) c)
                 (> (if use-text-flow
                      (Math/abs (- (double (:x0 c)) (double (:x1 (peek cur)))))
                      (if horizontal-ltr
                        (- (double (:x0 c)) (double (:x1 (peek cur))))
                        (- (double (:x0 (peek cur))) (double (:x1 c)))))
                    x-tolerance)))
        (recur (rest cs) [c] (conj words cur))

        (punctuation? split-at-punctuation (:text c))
        (recur (rest cs) [] (cond-> words
                              (seq cur) (conj cur)
                              true (conj [c])))

        :else
        (recur (rest cs) (conj cur c) words))
      (cond-> words (seq cur) (conj cur))))))

(defn- word-data [doc opts]
  (let [opts (merge {:x-tolerance default-tolerance
                     :y-tolerance default-tolerance
                     :horizontal-ltr true
                     :extra-attrs []
                     :expand-ligatures true}
                    (normalize-options opts))
        lines (cluster-lines (chars doc opts) (:y-tolerance opts)
                             (:use-text-flow opts))
        groups (mapv #(line-word-groups % opts) lines)]
    {:opts opts :lines lines :groups groups
     :words (mapv (fn [line-groups]
                    (mapv #(merge-word % (:extra-attrs opts)
                                       (:expand-ligatures opts))
                          line-groups))
                  groups)}))

(defn words
  "Vector of word maps `{:text :x0 :top :x1 :bottom :page-number}`, reading order.
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
  "Text output plus `[source-char output-character]` tuples. Inserted spaces and
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
  "Remove duplicate chars within positional `:tolerance`, comparing `:text`
   plus configurable `:compare-attrs` (default fontname, size, upright)."
  ([char-records] (dedupe-char-records char-records {}))
  ([char-records {:keys [tolerance compare-attrs extra-attrs]
                  :or {tolerance 1.0}}]
   (let [attrs (vec (distinct (cons :text (or compare-attrs extra-attrs
                                                [:fontname :size :upright]))))
         same? (fn [a b]
                 (and (every? #(= (get a %) (get b %)) attrs)
                      (<= (Math/abs (- (double (:x0 a)) (double (:x0 b))))
                          tolerance)
                      (<= (Math/abs (- (double (:doctop a)) (double (:doctop b))))
                          tolerance)))]
     (reduce (fn [kept c]
               (if (some #(same? % c) kept) kept (conj kept c)))
             [] char-records))))

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
  "Fast text reconstruction by doctop line clustering and direct character-gap
   spacing, without building word or text maps."
  ([doc] (extract-text-simple doc {}))
  ([doc {:keys [x-tolerance y-tolerance]
         :or {x-tolerance default-tolerance y-tolerance default-tolerance}
         :as opts}]
   (->> (simple-lines (chars doc opts) y-tolerance)
        (map #(collate-simple-line % x-tolerance))
        (str/join "\n"))))

(defn text
  "Reconstructed text: words joined by spaces within a line, lines by newlines.
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
