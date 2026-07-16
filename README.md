# pdfplumber-clj

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/pdfplumber-clj.svg)](https://clojars.org/net.clojars.savya/pdfplumber-clj)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/pdfplumber-clj)](https://cljdoc.org/d/net.clojars.savya/pdfplumber-clj/CURRENT)
[![test](https://github.com/jsavyasachi/pdfplumber-clj/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/pdfplumber-clj/actions/workflows/test.yml)

Plain-data PDF extraction for Clojure. Pull text, words, characters, geometric
objects, and tables out of digitally generated PDFs as EDN/JSON-friendly maps and
vectors — the Clojure counterpart to Python's [`pdfplumber`](https://github.com/jsvine/pdfplumber),
built on [Apache PDFBox](https://pdfbox.apache.org).

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=white" alt="Clojure" /></a>
<a href="https://pdfbox.apache.org"><img src="https://img.shields.io/badge/Apache_PDFBox-D22128?style=flat&logo=apache&logoColor=white" alt="Apache PDFBox" /></a>

## Status

Early release (`0.2.0`). The extraction API (text, words, chars, objects,
tables, crop) is in place and validated against the Python pdfplumber corpus;
it may still evolve before `1.0`.

## Install

deps.edn

```clojure
net.clojars.savya/pdfplumber-clj {:mvn/version "0.2.0"}
```

Leiningen

```clojure
[net.clojars.savya/pdfplumber-clj "0.2.0"]
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
and deterministic plain-data output.

Out (v1): PDF generation, OCR, scanned/image PDFs, AcroForm extraction, layout ML.
Table `:text` strategy is heuristic and intended for digitally generated PDFs.

## License

Copyright © 2026 Savyasachi.

Distributed under the Eclipse Public License 2.0.
