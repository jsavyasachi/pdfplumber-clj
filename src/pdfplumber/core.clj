(ns pdfplumber.core
  "Public API for pdfplumber-clj: open PDFs and extract text, words, characters,
   geometric objects, and tables as plain Clojure data."
  (:refer-clojure :exclude [chars filter])
  (:require [pdfplumber.document :as document]
            [pdfplumber.text :as text]
            [pdfplumber.objects :as objects]
            [pdfplumber.table :as table]
            [pdfplumber.page :as page])
  (:import [org.apache.pdfbox.pdmodel PDDocument]))

(set! *warn-on-reflection* true)

(defn open-pdf
  "Open a PDF, returning a document handle. See `pdfplumber.document/open-pdf`.
   The caller must close it (or use `with-pdf`)."
  [source]
  (document/open-pdf source))

(defn metadata
  "Document metadata map. See `pdfplumber.document/metadata`."
  [doc]
  (document/metadata doc))

(defn pages
  "Vector of page maps. See `pdfplumber.document/pages`."
  [doc]
  (document/pages doc))

(defn page
  "Page map for 1-based page number `n`. See `pdfplumber.document/page`."
  [doc n]
  (document/page doc n))

(defn crop-page
  "A cropped page view (restricts extraction to a bbox). See
   `pdfplumber.page/crop-page`."
  [doc opts]
  (page/crop-page doc opts))

(defn crop
  "Derived page crop with partial-object clipping."
  ([source bbox] (page/crop source bbox))
  ([source bbox opts] (page/crop source bbox opts)))

(defn within-bbox
  "Derived view containing only wholly enclosed objects."
  ([source bbox] (page/within-bbox source bbox))
  ([source bbox opts] (page/within-bbox source bbox opts)))

(defn outside-bbox
  "Derived view containing only objects outside a bbox."
  ([source bbox] (page/outside-bbox source bbox))
  ([source bbox opts] (page/outside-bbox source bbox opts)))

(defn filter-page
  "Derived view filtered by an object predicate."
  ([source pred] (page/filter source pred))
  ([source pred opts] (page/filter source pred opts)))

(def filter filter-page)

(defn page-view?
  "True when `x` is a cropped page view. See `pdfplumber.page/page-view?`."
  [x]
  (page/page-view? x))

(defn chars
  "Vector of character maps. Accepts a document handle or a cropped page view.
   See `pdfplumber.text/chars`."
  ([source] (chars source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (text/chars doc o))))

(defn words
  "Vector of word maps. Accepts a document handle or a cropped page view. See
   `pdfplumber.text/words`."
  ([source] (words source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (text/words doc o))))

(defn extract-words
  "Advanced word extraction. See `pdfplumber.text/extract-words`."
  ([source] (extract-words source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)]
                   (text/extract-words doc o))))

(defn word-map
  "Words and their contributing chars. See `pdfplumber.text/word-map`."
  ([source] (word-map source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)]
                   (text/word-map doc o))))

(defn text
  "Reconstructed text string. Accepts a document handle or a cropped page view.
   See `pdfplumber.text/text`."
  ([source] (text source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (text/text doc o))))

(defn extract-text
  "Advanced text extraction. See `pdfplumber.text/extract-text`."
  ([source] (extract-text source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)]
                   (text/extract-text doc o))))

(defn text-map
  "Text and its source-char mapping. See `pdfplumber.text/text-map`."
  ([source] (text-map source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)]
                   (text/text-map doc o))))

(defn objects
  "Vector of page object maps (lines, rects, curves, images). Accepts a document
   handle or a cropped page view. See `pdfplumber.objects/objects`."
  ([source] (objects source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (objects/objects doc o))))

(defn images
  "Vector of drawn image objects. Accepts a document handle or a cropped page
   view. See `pdfplumber.objects/images`."
  ([source] (images source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (objects/images doc o))))

(defn lines
  "Painted line objects. See `pdfplumber.objects/lines`."
  ([source] (lines source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (objects/lines doc o))))

(defn rects
  "Painted rectangle objects. See `pdfplumber.objects/rects`."
  ([source] (rects source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (objects/rects doc o))))

(defn curves
  "Painted curve objects. See `pdfplumber.objects/curves`."
  ([source] (curves source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (objects/curves doc o))))

(defn objects-by-type
  "Object maps grouped by type. See `pdfplumber.objects/objects-by-type`."
  ([source] (objects-by-type source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)]
                   (objects/objects-by-type doc o))))

(defn edges
  "Normalized page edges. See `pdfplumber.objects/edges`."
  ([source] (edges source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (objects/edges doc o))))

(defn horizontal-edges
  "Horizontal page edges. See `pdfplumber.objects/horizontal-edges`."
  ([source] (horizontal-edges source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)]
                   (objects/horizontal-edges doc o))))

(defn vertical-edges
  "Vertical page edges. See `pdfplumber.objects/vertical-edges`."
  ([source] (vertical-edges source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)]
                   (objects/vertical-edges doc o))))

(defn extract-table
  "Extract a single table. Accepts a document handle or a cropped page view. See
   `pdfplumber.table/extract-table`."
  ([source] (extract-table source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (table/extract-table doc o))))

(defn extract-tables
  "Extract tables as a vector. Accepts a document handle or a cropped page view.
   See `pdfplumber.table/extract-tables`."
  ([source] (extract-tables source {}))
  ([source opts] (let [[doc o] (page/resolve-source source opts)] (table/extract-tables doc o))))

(defmacro with-pdf
  "Open `source`, bind the document handle to `binding`, evaluate `body`, and
   always close the document on exit (including on exception).

       (with-pdf [doc \"statement.pdf\"]
         (text doc {:page 1}))"
  [[binding source] & body]
  `(let [doc# (document/open-pdf ~source)
         ~binding doc#]
     (try
       ~@body
       (finally
         (.close ^PDDocument doc#)))))
