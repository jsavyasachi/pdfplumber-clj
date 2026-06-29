(ns pdfplumber.text
  "Character, word, and text extraction over PDFBox's PDFTextStripper.

   PDFTextStripper already reports direction-adjusted coordinates in a top-left
   origin, so char maps are built directly from `getXDirAdj`/`getYDirAdj` without
   a page-height flip. Words are formed by clustering chars into lines (within
   `:y-tolerance`) and splitting on horizontal gaps wider than `:x-tolerance`."
  (:refer-clojure :exclude [chars])
  (:require [clojure.string :as str]
            [pdfplumber.geometry :as g])
  (:import [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.text PDFTextStripper TextPosition]
           [java.util List]))

(set! *warn-on-reflection* true)

(def ^:private default-tolerance 3.0)

(defn- tp->char [^TextPosition tp ^long page-no]
  (let [x0 (double (.getXDirAdj tp))
        w (double (.getWidthDirAdj tp))
        h (double (.getHeightDir tp))
        bottom (double (.getYDirAdj tp))]
    {:text (.getUnicode tp)
     :x0 x0
     :top (- bottom h)
     :x1 (+ x0 w)
     :bottom bottom
     :font-name (some-> (.getFont tp) .getName)
     :font-size (double (.getFontSizeInPt tp))
     :page-number page-no}))

(defn- char-bbox [c]
  [(:x0 c) (:top c) (:x1 c) (:bottom c)])

(defn- collecting-stripper
  "A PDFTextStripper that appends each char map (tagged with `page-no`) to `acc`."
  ^PDFTextStripper [acc ^long page-no]
  (proxy [PDFTextStripper] []
    (writeString [^String _text ^List text-positions]
      (doseq [^TextPosition tp text-positions]
        (swap! acc conj (tp->char tp page-no))))))

(defn- page-chars [^PDDocument doc ^long p]
  (let [acc (atom [])
        ^PDFTextStripper stripper (collecting-stripper acc p)]
    (.setSortByPosition stripper true)
    (.setStartPage stripper (int p))
    (.setEndPage stripper (int p))
    (.getText stripper doc)
    @acc))

(defn chars
  "Vector of character maps `{:text :x0 :top :x1 :bottom :font-name :font-size
   :page-number}`. Options: `:page` (1-based, limit to one page) and `:bbox`
   (keep chars whose center falls inside `[x0 top x1 bottom]`)."
  ([doc] (chars doc {}))
  ([^PDDocument doc {:keys [page bbox]}]
   (let [pages (if page [(long page)] (range 1 (inc (.getNumberOfPages doc))))
         cs (into [] (mapcat #(page-chars doc %)) pages)]
     (cond->> cs
       bbox (filterv #(g/within? bbox (g/center (char-bbox %))))))))

(defn- whitespace? [s]
  (or (nil? s) (str/blank? s)))

(defn- cluster-lines
  "Group chars into lines, top-to-bottom, by `:top` within `y-tol`."
  [chars y-tol]
  (reduce (fn [acc c]
            (let [line (peek acc)
                  ltop (some-> line first :top)]
              (if (and ltop (<= (Math/abs (- (double (:top c)) (double ltop))) y-tol))
                (conj (pop acc) (conj line c))
                (conj acc [c]))))
          []
          (sort-by :top chars)))

(defn- merge-word [cs]
  {:text (apply str (map :text cs))
   :x0 (:x0 (first cs))
   :top (reduce min (map :top cs))
   :x1 (:x1 (last cs))
   :bottom (reduce max (map :bottom cs))
   :page-number (:page-number (first cs))})

(defn- line-words
  "Split a line's chars (sorted left-to-right, whitespace retained) into words.
   A whitespace char or a gap wider than `x-tol` starts a new word."
  [line x-tol]
  (loop [cs (sort-by :x0 line), cur [], words []]
    (if-let [c (first cs)]
      (cond
        (whitespace? (:text c))
        (recur (rest cs) [] (cond-> words (seq cur) (conj cur)))

        (and (seq cur)
             (> (- (double (:x0 c)) (double (:x1 (peek cur)))) x-tol))
        (recur (rest cs) [c] (conj words cur))

        :else
        (recur (rest cs) (conj cur c) words))
      (mapv merge-word (cond-> words (seq cur) (conj cur))))))

(defn words
  "Vector of word maps `{:text :x0 :top :x1 :bottom :page-number}`, reading order.
   Options: `:page`, `:bbox`, `:x-tolerance` (default 3.0), `:y-tolerance`
   (default 3.0)."
  ([doc] (words doc {}))
  ([doc {:keys [x-tolerance y-tolerance] :or {x-tolerance default-tolerance
                                              y-tolerance default-tolerance}
         :as opts}]
   (into [] (mapcat #(line-words % x-tolerance))
         (cluster-lines (chars doc opts) y-tolerance))))

(defn text
  "Reconstructed text: words joined by spaces within a line, lines by newlines.
   Accepts the same options as `words`."
  ([doc] (text doc {}))
  ([doc {:keys [x-tolerance y-tolerance] :or {x-tolerance default-tolerance
                                              y-tolerance default-tolerance}
         :as opts}]
   (->> (cluster-lines (chars doc opts) y-tolerance)
        (map (fn [line]
               (->> (line-words line x-tolerance)
                    (map :text)
                    (str/join " "))))
        (str/join "\n"))))
