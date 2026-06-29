(ns pdfplumber.core
  "Public API for pdfplumber-clj: open PDFs and extract text, words, characters,
   geometric objects, and tables as plain Clojure data."
  (:require [pdfplumber.document :as document])
  (:import [org.apache.pdfbox.pdmodel PDDocument]))

(set! *warn-on-reflection* true)

(defn open-pdf
  "Open a PDF, returning a document handle. See `pdfplumber.document/open-pdf`.
   The caller must close it (or use `with-pdf`)."
  [source]
  (document/open-pdf source))

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
