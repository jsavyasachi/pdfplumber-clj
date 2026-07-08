# Text, Words & Objects

Require the public API namespace:

```clojure
(require '[pdfplumber.core :as pdf])
```

Text and object functions accept either a document handle or a cropped page view
from `crop-page`.

## Characters

`chars` returns one map per PDF text position:

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (pdf/chars doc {:page 1}))
;; => [{:text "H"
;;      :x0 72.0
;;      :top 82.5
;;      :x1 80.7
;;      :bottom 94.5
;;      :font-name "Helvetica"
;;      :font-size 12.0
;;      :page-number 1}
;;     ...]
```

Character map keys:

- `:text`
- `:x0`
- `:top`
- `:x1`
- `:bottom`
- `:font-name`
- `:font-size`
- `:page-number`

Options:

- `:page` - 1-based page number.
- `:bbox` - keep chars whose bbox center falls inside `[x0 top x1 bottom]`.

## Words

`words` groups characters into word maps:

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

Word map keys:

- `:text`
- `:x0`
- `:top`
- `:x1`
- `:bottom`
- `:page-number`

Options:

- `:page` - 1-based page number.
- `:bbox` - passed to `chars`, so it keeps characters whose centers are inside
  the bounding box before word grouping.
- `:x-tolerance` - default `3.0`. A horizontal gap wider than this starts a new
  word.
- `:y-tolerance` - default `3.0`. Characters whose `:top` values are within
  this tolerance are grouped into the same line.

## Text

`text` reconstructs a string from words:

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (pdf/text doc {:page 1}))
;; => "Hello PDF"
```

It accepts the same options as `words`. Words are joined by spaces within a line.
Lines are joined with newline characters.

## Graphical Objects

`objects` extracts painted paths as line, rectangle, and curve maps:

```clojure
(pdf/with-pdf [doc "diagram.pdf"]
  (pdf/objects doc {:page 1}))
;; => [{:type :line
;;      :x0 72.0
;;      :top 92.0
;;      :x1 540.0
;;      :bottom 92.0
;;      :orientation :horizontal
;;      :page-number 1}
;;     {:type :rect
;;      :x0 100.0
;;      :top 292.0
;;      :x1 300.0
;;      :bottom 392.0
;;      :page-number 1}]
```

Common object keys:

- `:type` - one of `:line`, `:rect`, or `:curve`.
- `:x0`
- `:top`
- `:x1`
- `:bottom`
- `:page-number`

Line objects also include:

- `:orientation` - `:horizontal`, `:vertical`, or `:other`.

Options:

- `:page` - 1-based page number.
- `:types` - a set of object types to keep, for example `#{:line}`.
- `:bbox` - keep objects whose bounding boxes intersect the given bbox.

Only painted paths yield objects. Clip-only and no-paint paths are discarded.

## Cropping

`crop-page` creates a lightweight cropped view:

```clojure
(pdf/with-pdf [doc "statement.pdf"]
  (let [view (pdf/crop-page doc {:page 1
                                 :bbox [70.0 80.0 110.0 100.0]})]
    {:is-view? (pdf/page-view? view)
     :words (pdf/words view)
     :text (pdf/text view)
     :objects (pdf/objects view)}))
```

The view does not copy page data and does not translate coordinates. It resolves
to extraction options:

```clojure
{:page 1
 :bbox [70.0 80.0 110.0 100.0]}
```

Explicit options passed to an extraction call override the view's values:

```clojure
(pdf/words view {:page 2})
```

Coordinate behavior in cropped views:

- text filtering keeps characters whose bbox centers are inside the crop bbox
- object filtering keeps objects whose bboxes intersect the crop bbox
- returned maps keep original page coordinates
- returned bboxes are not clipped to the crop bbox
