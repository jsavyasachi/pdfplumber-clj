# Extracting Tables

`pdfplumber.core` exposes two table entry points:

```clojure
(pdf/extract-table doc opts)
(pdf/extract-tables doc opts)
```

Both also accept a cropped page view from `crop-page`. `extract-table` returns
one table map. `extract-tables` returns a vector and currently returns at most
one table.

## Return Shape

Tables have this shape:

```clojure
{:page-number 1
 :strategy :lines
 :bbox [72.0 92.0 540.0 152.0]
 :rows [[{:text "Date" :bbox [72.0 92.0 300.0 122.0]}
         {:text "Amount" :bbox [300.0 92.0 540.0 122.0]}]
        [{:text "2026-01-01" :bbox [72.0 122.0 300.0 152.0]}
         {:text "$10.00" :bbox [300.0 122.0 540.0 152.0]}]]
 :cells [[72.0 92.0 300.0 122.0]
         [300.0 92.0 540.0 122.0]
         [72.0 122.0 300.0 152.0]
         [300.0 122.0 540.0 152.0]]
 :debug {:horizontal-lines 3
         :vertical-lines 3
         :cells 4}}
```

`:rows` is the main result: a vector of rows, each a vector of cell maps. Each
cell has `:text` and `:bbox`.

The exact coordinates above are illustrative. The shape matches the API.

## Options

Shared options:

- `:page` - 1-based page number.
- `:bbox` - restrict extraction to `[x0 top x1 bottom]`.
- `:strategy` - `:lines` by default, or `:text`.

`:lines` option:

- `:snap-tolerance` - default `3.0`. Near-collinear ruling positions within this
  tolerance are snapped together.

`:text` options:

- `:text-x-tolerance` - default `3.0`. Groups similar word left edges into
  inferred columns.
- `:text-y-tolerance` - default `3.0`. Groups words into rows.
- `:min-words-vertical` - default `3`. Minimum words required to support an
  inferred column left edge.
- `:min-words-horizontal` - default `1`. Minimum non-blank cells required to
  keep a row.

Table extraction also passes options through to word/object extraction. That
means `:page`, `:bbox`, `:x-tolerance`, `:y-tolerance`, and `:types` can affect
the data the table strategies see. In normal use, prefer the table-specific
options above plus `:page` and `:bbox`.

## Lines Strategy

Use `:lines` for ruled tables: tables with drawn horizontal and vertical lines,
including rectangle edges.

```clojure
(pdf/with-pdf [doc "invoice.pdf"]
  (pdf/extract-table doc {:page 1 :strategy :lines}))
```

The implementation:

- extracts graphical objects from the page
- uses horizontal and vertical `:line` objects plus `:rect` sides as table edges
- snaps nearby x/y positions using `:snap-tolerance`
- finds grid cells whose four corners are intersections
- assigns words to cells by word-center containment

The returned table includes `:cells` for the grid cell bounding boxes and
`:debug` counts for `:horizontal-lines`, `:vertical-lines`, and `:cells`.

## Text Strategy

Use `:text` for whitespace-aligned tables without ruling lines.

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (pdf/extract-table doc {:page 1
                          :strategy :text
                          :text-x-tolerance 3.0
                          :text-y-tolerance 3.0}))
```

The implementation:

- extracts words
- groups words into rows by `:top` within `:text-y-tolerance`
- infers column left edges from repeated word `:x0` positions within
  `:text-x-tolerance`
- keeps column edges supported by at least `:min-words-vertical` words
- filters rows by `:min-words-horizontal`

The returned table includes `:rows`, `:bbox`, and `:debug` with `:rows` and
`:columns`. It does not include `:cells` because cells are inferred from text
bands rather than a ruled grid.

## Cropping Before Extraction

Use `crop-page` to isolate the region that contains the table. A cropped page
view carries the original document handle plus `:page` and `:bbox`; extraction
functions accept it in place of a document.

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (let [table-region (pdf/crop-page doc {:page 1
                                         :bbox [40.0 250.0 560.0 520.0]})]
    (pdf/extract-table table-region {:strategy :lines})))
```

Explicit options override the view:

```clojure
(pdf/extract-table table-region {:page 2 :strategy :text})
```

Cropping filters extraction to the view's bounding box. Coordinates in returned
rows, cells, words, and objects remain in the original page coordinate system.
