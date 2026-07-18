# pdfplumber-clj

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/pdfplumber-clj.svg)](https://clojars.org/net.clojars.savya/pdfplumber-clj)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/pdfplumber-clj)](https://cljdoc.org/d/net.clojars.savya/pdfplumber-clj/CURRENT)
[![test](https://github.com/jsavyasachi/pdfplumber-clj/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/pdfplumber-clj/actions/workflows/test.yml)

PDF extraction and inspection for Clojure, built on [Apache PDFBox](https://pdfbox.apache.org).
The Clojure counterpart to Python's [`pdfplumber`](https://github.com/jsvine/pdfplumber):
pull text, tables, and geometry out of digitally generated PDFs as plain,
EDN/JSON-friendly data.

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=white" alt="Clojure" /></a>
<a href="https://pdfbox.apache.org"><img src="https://img.shields.io/badge/Apache_PDFBox-D22128?style=flat&logo=apache&logoColor=white" alt="Apache PDFBox" /></a>

## Status

Actively developed (`0.6.0`). Pre-`1.0`, so data shapes may still refine.

Covers the full Python pdfplumber extraction surface (text, words, chars,
objects, tables, crop), validated against the pdfplumber corpus, plus:

- **Streaming** extraction: reducible/transducer, page-at-a-time
- **Visual debugging**: page render + overlays
- **Tagged structure** trees
- **Document metadata**: forms, outline, attachments, permissions, signatures
- **CLI**: CSV/JSON object dump

## Install

deps.edn

```clojure
net.clojars.savya/pdfplumber-clj {:mvn/version "0.6.0"}
```

Leiningen

```clojure
[net.clojars.savya/pdfplumber-clj "0.6.0"]
```

Requires JDK 17+.

## Quickstart

```clojure
(require '[pdfplumber.core :as pdf])

(pdf/with-pdf [doc "statement.pdf"]
  (pdf/text doc {:page 1}))           ; => "Account statement\n..."

(pdf/with-pdf [doc "statement.pdf"]
  (pdf/words doc {:page 1}))          ; => [{:text "Account" :x0 .. :top .. :x1 .. :bottom ..} ...]

(pdf/with-pdf [doc "invoice.pdf"]
  (pdf/extract-table doc {:page 1 :strategy :lines}))
```

## Streaming extraction

The `reducible-*` functions on `pdfplumber.core` return `IReduceInit` streams
that extract one page at a time. Transducers terminate early without extracting
later pages. They are re-exported from `pdfplumber.reducible`.

```clojure
(into []
      (comp (filter #(> (:size %) 10)) (take 100))
      (pdf/reducible-chars doc))

(transduce (take 20) conj [] (pdf/reducible-words doc))
```

## Tables

`extract-tables` returns every independent table region on a page, ordered
top-to-bottom then left-to-right. `extract-table` returns only the first region.
Configure each axis independently with `:vertical-strategy` and
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
Explicit lines may be coordinates or maps with bounded line coordinates.

## Tables as data

`table->maps` converts an extracted table or raw rows to a sequence of
header-keyed maps.

```clojure
(-> (pdf/extract-table doc {:page 1})
    pdf/table->maps)
```

The result feeds `tech.v3.dataset/->dataset` directly with no added dependency.
Use `:keywordize? true` for keyword keys. Set `:header` to `:first` (the
default), an explicit key vector, or `false` for integer keys.

## Visual debugging

`pdfplumber.core/to-image` renders a page through PDFBox and returns a
`PageImage`; `pdfplumber.core/page-image?` identifies one. Overlay, reset/copy,
save, and display verbs live in `pdfplumber.image`.

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

Other overlays: `draw-line`, `draw-vline`, `draw-hline`, `draw-rects`,
`draw-circle`, `draw-circles`, and `outline-chars`. `reset`, `copy`, `save`, and
`show` manage the rendered image.

## Structure tree

`structure-tree` returns a tagged PDF's nested logical structure.
`page-structure-tree` restricts it to a 1-based page. Untagged PDFs return `[]`.

```clojure
(pdf/structure-tree doc)
(pdf/page-structure-tree doc 1)
```

## Form fields

`form-fields` returns terminal AcroForm field maps with values, constraints,
options, and first-widget geometry. `field-values` returns the name-to-value
map.

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

`signatures` surfaces signature metadata plus a `:covers-whole-document?`
integrity signal. `signed?` reports signature-dictionary presence. These APIs do
not validate cryptographic signatures, certificates, or trust.

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

`images` returns drawn image objects. Images also appear as `:image` entries in
`objects`. Each image includes its bbox, pixel `:width` and `:height`,
`:colorspace`, `:bits`, `:mask?`, and `:smask?`. Decoded PNG bytes are omitted
by default.

```clojure
(pdf/images doc {:page 1})
(pdf/images doc {:page 1 :include-image-data? true}) ; adds :bytes
```

## Coordinate system

Public coordinates use a **top-left origin** (matching `pdfplumber`), with bounding
boxes as `[x0 top x1 bottom]` in PDF user-space points. PDFBox's native bottom-left
coordinates are converted internally.

## Scope

In: text/word/char and image extraction, page geometry, crop/filter,
multi-table extraction from ruling lines, text alignment, or explicit lines,
visual debugging, tagged-PDF structure trees, first-class form fields,
outline/bookmarks, attachments, permissions, signature metadata, command-line
CSV/JSON export, and deterministic plain-data output.

Not in scope (same as Python pdfplumber): PDF generation, OCR, scanned/image
PDFs, and layout ML. Signature APIs do not perform cryptographic, certificate,
or trust validation. Table `:text` strategy is heuristic and intended for
digitally generated PDFs.

## License

Copyright © 2026 Savyasachi.

Distributed under the Eclipse Public License 2.0.
