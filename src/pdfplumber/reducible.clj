(ns pdfplumber.reducible
  "Single-pass, page-at-a-time object extraction."
  (:require [pdfplumber.objects :as objects]
            [pdfplumber.page :as page]
            [pdfplumber.text :as text])
  (:import [org.apache.pdfbox.pdmodel PDDocument]))

(set! *warn-on-reflection* true)

(defn- page-numbers [^PDDocument doc opts]
  (if-let [page-number (:page opts)]
    [(long page-number)]
    (range 1 (inc (.getNumberOfPages doc)))))

(defn- preserve-reduced [f]
  (fn [acc value]
    (let [result (f acc value)]
      (if (reduced? result) (reduced result) result))))

(defn- reducible-pages [source extractor opts]
  (let [[resolved-doc resolved-opts] (page/resolve-source source opts)
        ^PDDocument doc resolved-doc
        pages (page-numbers doc resolved-opts)
        extract-page (fn [page-number]
                       (extractor doc (assoc resolved-opts :page page-number)))]
    (reify
      clojure.lang.IReduceInit
      (reduce [_ f init]
        (loop [acc init
               remaining pages]
          (if-let [page-number (first remaining)]
            (let [next-acc (reduce (preserve-reduced f)
                                   acc
                                   (extract-page page-number))]
              (if (reduced? next-acc)
                @next-acc
                (recur next-acc (next remaining))))
            acc)))

      clojure.lang.Seqable
      (seq [_]
        (letfn [(step [remaining]
                  (lazy-seq
                   (when-let [page-number (first remaining)]
                     (concat (extract-page page-number)
                             (step (next remaining))))))]
          (seq (step pages)))))))

(defn page-reducible
  "Reducible page stream from `(extractor doc page-opts)`. Extracts one page at
   a time and stops before later pages when reduction terminates early."
  ([source extractor] (page-reducible source extractor {}))
  ([source extractor opts] (reducible-pages source extractor opts)))

(defn reducible-chars
  "Single-pass reducible character stream. Extracts one page at a time and a
   reduced result stops extraction before later pages are visited."
  ([source] (reducible-chars source {}))
  ([source opts] (page-reducible source text/chars opts)))

(defn reducible-words
  "Reducible word stream, extracted one page at a time."
  ([source] (reducible-words source {}))
  ([source opts] (page-reducible source text/words opts)))

(defn reducible-objects
  "Reducible page-object stream."
  ([source] (reducible-objects source {}))
  ([source opts] (page-reducible source objects/objects opts)))

(defn reducible-lines
  "Reducible painted-line stream."
  ([source] (reducible-lines source {}))
  ([source opts] (page-reducible source objects/lines opts)))

(defn reducible-rects
  "Reducible painted-rectangle stream."
  ([source] (reducible-rects source {}))
  ([source opts] (page-reducible source objects/rects opts)))

(defn reducible-curves
  "Reducible painted-curve stream."
  ([source] (reducible-curves source {}))
  ([source opts] (page-reducible source objects/curves opts)))

(defn reducible-images
  "Reducible drawn-image stream."
  ([source] (reducible-images source {}))
  ([source opts] (page-reducible source objects/images opts)))

(defn reducible-annots
  "Reducible annotation stream."
  ([source] (reducible-annots source {}))
  ([source opts] (page-reducible source objects/annots opts)))
