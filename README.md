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

Early release (`0.1.2`). The extraction API (text, words, chars, objects,
tables, crop) is in place and validated against the Python pdfplumber corpus;
it may still evolve before `1.0`.

## Install

deps.edn

```clojure
net.clojars.savya/pdfplumber-clj {:mvn/version "0.1.2"}
```

Leiningen

```clojure
[net.clojars.savya/pdfplumber-clj "0.1.2"]
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

## Coordinate system

Public coordinates use a **top-left origin** (matching `pdfplumber`), with bounding
boxes as `[x0 top x1 bottom]` in PDF user-space points. PDFBox's native bottom-left
coordinates are converted internally.

## Scope

In: text/word/char extraction, page geometry, crop/filter, ruling-line and
text-aligned table extraction, deterministic plain-data output.

Out (v1): PDF generation, OCR, scanned/image PDFs, AcroForm extraction, layout ML.
Table `:text` strategy is heuristic and intended for digitally generated PDFs.

## License

Copyright © 2026 Savyasachi.

Distributed under the Eclipse Public License 2.0.
