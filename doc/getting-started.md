# Getting Started

`pdfplumber-clj` extracts plain Clojure data from digitally generated PDFs. It
uses Apache PDFBox and follows Python's `pdfplumber`.

Require the public API namespace:

```clojure
(require '[pdfplumber.core :as pdf])
```

## Install

deps.edn:

```clojure
net.clojars.savya/pdfplumber-clj {:mvn/version "1.11.0"}
```

Leiningen:

```clojure
[net.clojars.savya/pdfplumber-clj "1.11.0"]
```

Requires JDK 17+.

## Open and Close a Document

`open-pdf` accepts a path string, `java.io.File`, byte array, `java.io.InputStream`,
or an already-open `org.apache.pdfbox.pdmodel.PDDocument`.

```clojure
(let [doc (pdf/open-pdf "statement.pdf")]
  (try
    (pdf/text doc {:page 1})
    (finally
      (.close doc))))
```

Most code should use `with-pdf`. It closes the document when the body exits,
including after an exception:

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (pdf/text doc {:page 1}))
```

PDF loading errors are thrown as `clojure.lang.ExceptionInfo` with
`:pdfplumber/error` in `ex-data`. Current error values are `:invalid-input`,
`:encrypted-pdf`, and `:parse-failed`.

## Pages and Metadata

`metadata` returns a map with `:page-count` and any document information fields
present in the PDF:

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (pdf/metadata doc))
;; => {:page-count 1
;;     :title "Q2 Statement"
;;     :author "Acme Bank"}
```

Metadata keys can be `:page-count`, `:title`, `:author`, `:subject`,
`:keywords`, `:creator`, `:producer`, `:creation-date`, and
`:modification-date`. Dates are ISO-8601 strings.

`pages` returns page maps in document order. `page` returns one page by 1-based
page number.

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (pdf/pages doc))
;; => [{:page-number 1
;;      :width 612.0
;;      :height 792.0
;;      :rotation 0
;;      :bbox [0.0 0.0 612.0 792.0]}]

(pdf/with-pdf [doc "statement.pdf"]
  (pdf/page doc 1))
```

If the page number is out of range, `page` throws `ExceptionInfo` with
`:pdfplumber/error :page-not-found`, plus `:page` and `:page-count`.

## First Text Extraction

Use `text` for a reconstructed string from a page:

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (pdf/text doc {:page 1}))
;; => "Hello PDF"
```

Use `words` when you need positions:

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (pdf/words doc {:page 1}))
;; => [{:text "Hello"
;;      :x0 72.0
;;      :top 82.5
;;      :x1 99.3
;;      :bottom 94.5
;;      :page-number 1}
;;     {:text "PDF" ...}]
```

The coordinate values in these examples are illustrative. Exact values depend
on PDF font metrics.

## Plain Data

The API returns Clojure maps, vectors, strings, numbers, and keywords. That data
is EDN/JSON-friendly and does not expose PDFBox objects in extraction results.

The public coordinate system uses PDF user-space points and a top-left origin.
A bounding box always has this form:

```clojure
[x0 top x1 bottom]
```

Rules:

- `x0 <= x1`
- `top <= bottom`
- `x` increases left to right
- `y` increases top to bottom
- page maps use `:bbox [0.0 0.0 width height]`

PDFBox uses a bottom-left origin for graphics. `pdfplumber-clj` converts graphic
object coordinates to the public top-left coordinate system. PDFBox's text
stripper adjusts text positions to top-left coordinates before it makes character
maps.
