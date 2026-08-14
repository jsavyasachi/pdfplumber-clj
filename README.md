# pdfplumber-clj

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/pdfplumber-clj.svg)](https://clojars.org/net.clojars.savya/pdfplumber-clj)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/pdfplumber-clj)](https://cljdoc.org/d/net.clojars.savya/pdfplumber-clj/CURRENT)
[![test](https://github.com/jsavyasachi/pdfplumber-clj/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/pdfplumber-clj/actions/workflows/test.yml)
[![parity](https://github.com/jsavyasachi/pdfplumber-clj/actions/workflows/parity.yml/badge.svg)](https://github.com/jsavyasachi/pdfplumber-clj/actions/workflows/parity.yml)

Extract and inspect PDFs in Clojure with [Apache PDFBox](https://pdfbox.apache.org).
This library is the Clojure counterpart to Python's [`pdfplumber`](https://github.com/jsvine/pdfplumber).
It extracts text, tables, and geometry from digitally generated PDFs as plain,
EDN/JSON-friendly data.

**[Try it live](https://savyasachi.dev/tools/pdf-tables)** - a hosted table extractor for this
library. Upload a PDF to view detected table regions on each page. Set the detection
strategy. Download the rows as CSV or JSON. The service processes files in memory and does not store them.

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=white" alt="Clojure" /></a>
<a href="https://pdfbox.apache.org"><img src="https://img.shields.io/badge/Apache_PDFBox-D22128?style=flat&logo=apache&logoColor=white" alt="Apache PDFBox" /></a>

## Status

Stable. Data shapes follow semantic versioning. A breaking change to a
returned map requires a major bump.

Covers the Python pdfplumber extraction surface for text, words, chars,
objects, tables, and crop. It also provides:

- **Streaming** extraction: reducible/transducer, page-at-a-time
- **Visual debugging**: page render + overlays
- **Tagged structure** trees
- **Document metadata**: forms, outline, attachments, permissions, signatures
- **CLI**: CSV/JSON object dump

The `parity` workflow measures parity weekly. It fetches the upstream
`jsvine/pdfplumber` test corpus. It extracts each PDF with Python pdfplumber to
make a baseline. It compares page count, text similarity, word count, table cell
content, and page object counts and boxes. The repository does not commit the corpus. Run
`dev/fetch-corpus.sh`, then `dev/gen_golden.py`.

## Install

deps.edn

```clojure
net.clojars.savya/pdfplumber-clj {:mvn/version "1.5.0"}
```

Leiningen

```clojure
[net.clojars.savya/pdfplumber-clj "1.5.0"]
```

Requires JDK 17+.

## Quickstart

```clojure
(require '[pdfplumber.core :as pdf])

(pdf/with-pdf [doc "statement.pdf"]
  (pdf/text doc {:page 1}))           ; => "Account statement\n..."

;; Supply :password for password-protected PDFs.
(pdf/with-pdf [doc "statement.pdf" {:password "hunter2"}]
  (pdf/text doc {:page 1}))

(pdf/with-pdf [doc "statement.pdf"]
  (pdf/words doc {:page 1}))          ; => [{:text "Account" :x0 .. :top .. :x1 .. :bottom ..} ...]

(pdf/with-pdf [doc "invoice.pdf"]
  (pdf/extract-table doc {:page 1 :strategy :lines}))
```

## Streaming extraction

The `reducible-*` functions in `pdfplumber.core` return `IReduceInit` streams.
They extract one page at a time. Transducers can stop early without extracting
later pages. `pdfplumber.reducible` also exports these functions.

```clojure
(into []
      (comp (filter #(> (:size %) 10)) (take 100))
      (pdf/reducible-chars doc))

(transduce (take 20) conj [] (pdf/reducible-words doc))
```

## Tables

`extract-tables` returns independent table regions with more than one cell. It
orders them top-to-bottom, then left-to-right. `extract-table` returns the first
region.
Set each axis with `:vertical-strategy` and
`:horizontal-strategy`; each accepts `:lines`, `:lines-strict`, `:text`, or
`:explicit`. The legacy `:strategy` option sets both axes.

```clojure
(pdf/extract-tables doc
  {:page 1
   :vertical-strategy :explicit
   :horizontal-strategy :lines
   :explicit-vertical-lines [70 170 260]
   :snap-tolerance 3.0
   :join-tolerance 3.0
   :edge-min-length 3.0
   :intersection-tolerance 3.0
   :min-words-vertical 3
   :min-words-horizontal 1})
```

Use `:explicit-horizontal-lines` with `:horizontal-strategy :explicit`.
Explicit lines can be coordinates or maps with bounded line coordinates.

## Tables as data

`table->maps` converts an extracted table or raw rows to a sequence of
header-keyed maps.

```clojure
(-> (pdf/extract-table doc {:page 1})
    pdf/table->maps)
```

The result can go directly to `tech.v3.dataset/->dataset` with no added dependency.
Use `:keywordize? true` for keyword keys. Set `:header` to `:first`, the
default, an explicit key vector, or `false` for integer keys.

## Visual debugging

`pdfplumber.core/to-image` renders a page with PDFBox and returns a `PageImage`.
`pdfplumber.core/page-image?` identifies a `PageImage`. `pdfplumber.image` has
functions to overlay, reset, copy, save, and display images.

```clojure
(require '[pdfplumber.image :as image])

(pdf/with-pdf [doc "invoice.pdf"]
  (-> (pdf/to-image doc {:page 1 :resolution 144})
      (image/outline-words)
      (image/draw-rect [72 72 240 160])
      (image/save "debug.png")))

(pdf/with-pdf [doc "invoice.pdf"]
  (-> (pdf/to-image doc {:page 1})
      (image/debug-tablefinder {:vertical-strategy :lines})
      (image/save "tables.png")))
```

More verbs in `pdfplumber.image`:

- Draw: `draw-line`, `draw-vline`, `draw-hline`, `draw-rect`, `draw-rects`, `draw-circle`, `draw-circles`
- Outline: `outline-words`, `outline-chars`
- Manage: `reset`, `copy`, `save`, `show`

## Structure tree

`structure-tree` returns the nested logical structure of a tagged PDF.
`page-structure-tree` limits it to a 1-based page. Untagged PDFs return `[]`.

```clojure
(pdf/structure-tree doc)
(pdf/page-structure-tree doc 1)
```

## Form fields

`form-fields` returns terminal AcroForm field maps. They include values,
constraints, options, and first-widget geometry. `field-values` returns the
name-to-value map.

```clojure
(pdf/form-fields doc)
;; => [{:name "customer.email", :type :text, :value "ada@example.com",
;;      :required? true, :read-only? false, :page-number 1,
;;      :bbox [72.0 120.0 288.0 140.0]}]

(pdf/field-values doc)
;; => {"customer.email" "ada@example.com"}
```

Widget annotations from `annots` also carry `:field-name`, `:field-value`, and
`:field-type`.

## Document outline

`outline` returns nested bookmarks with resolved 1-based page numbers.

```clojure
(pdf/outline doc)
;; => [{:title "Introduction", :page-number 1, :children []}]
```

## Attachments

`attachments` returns embedded-file metadata. Set `:include-data? true` to add
decoded `:bytes`.

```clojure
(pdf/attachments doc)
;; => [{:name "data.csv", :size 128, :mime-type "text/csv"}]

(pdf/attachments doc {:include-data? true})
```

## Permissions

`permissions` reports encryption state and effective access flags.

```clojure
(pdf/permissions doc)
;; => {:encrypted? true, :can-print? true, :can-modify? false, ...}
```

## Signatures

`signatures` returns signature metadata and a `:covers-whole-document?`
integrity signal. `signed?` reports the presence of a signature dictionary.
These APIs do not validate cryptographic signatures, certificates, or trust.

```clojure
(pdf/signatures doc)
;; => [{:name "Ada Lovelace", :byte-range [0 1024 2048 512],
;;      :covers-whole-document? true}]

(pdf/signed? doc)
;; => true
```

## CLI

Dump selected PDF objects as CSV or JSON:

```shell
clojure -M -m pdfplumber.cli statement.pdf \
  --format json --pages 1,2 --types char,line,rect,curve,image,annot \
  --precision 2 --indent 2
```

## Images

`images` returns drawn image objects. They also appear as `:image` entries in
`objects`. Each object has:

- `:bbox`, plus pixel `:width` and `:height`
- `:colorspace` and `:bits`
- `:mask?` and `:smask?`

Decoded PNG bytes are omitted by default.

```clojure
(pdf/images doc {:page 1})
(pdf/images doc {:page 1 :include-image-data? true}) ; adds :bytes
```

## Coordinate system

Public coordinates use a **top-left origin**, like `pdfplumber`. Bounding boxes
are `[x0 top x1 bottom]` in PDF user-space points. The library converts PDFBox's
native bottom-left coordinates.

## Scope

In:

- Text, word, char, and image extraction
- Page geometry, crop, and filter
- Multi-table extraction (ruling lines, text alignment, or explicit lines)
- Visual debugging and tagged-PDF structure trees
- Form fields, outline/bookmarks, attachments, permissions, and signature metadata
- Command-line CSV/JSON export
- Deterministic plain-data output

Not in scope, as in Python pdfplumber: PDF generation, OCR, scanned/image PDFs,
and layout ML.

Two caveats:

- Signature APIs do not perform cryptographic, certificate, or trust validation.
- The table `:text` strategy is heuristic for digitally generated PDFs.

## License

Copyright © 2026 Savyasachi.

Distributed under the Eclipse Public License 2.0.
