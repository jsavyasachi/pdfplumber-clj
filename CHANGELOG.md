# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Character associations and text spans for tagged-PDF structure elements,
  with explicit `:exact` and `:unmapped` confidence.

## [1.11.0] - 2026-08-27

### Added
- AcroForm field filling, appearance refresh, flattening, and FDF import/export.

## [1.10.0] - 2026-08-14

### Fixed
- Path and image coordinates under a transformed coordinate system keep the
  decimals the document holds. The transform ran on 32-bit floats, so a curve,
  a line, or an image placed through a scaled or translated matrix landed a
  fraction of a point away from where the document puts it.

## [1.9.0] - 2026-08-13

### Fixed
- Graphics coordinates keep the decimal the document holds. A coordinate is
  stored as a 32-bit float, and widening it to a double added the digits of the
  binary expansion, so a coordinate written as 841.68 read as 841.6799926757812.
- A rectangle operator with a negative width or height takes its bounds from the
  operator arguments. The bounds came from the corner points, which apply the
  arithmetic in a different order and shift the edge.

## [1.8.0] - 2026-08-13

### Fixed
- Rectangles, lines, curves, images, and annotations take the page rotation. They
  kept the coordinates of the unrotated page, so on a rotated page every graphics
  object was placed on the wrong axis. Characters already took the rotation, so a
  rotated page gave text and graphics in two different coordinate spaces.

### Changed
- Table edges come from graphics objects that already carry the page rotation, so
  the table builder no longer rotates its own edges.

## [1.7.0] - 2026-08-13

### Fixed
- Cell text comes from the characters inside the cell, grouped into words in the
  cell. It came from the words of the page, so a word that the cell holds in part
  gave the wrong text. Characters group into a line only when they share the
  upright state, the font, and the size.
- A path that ends with a move gives no object for that move. The extra subpath
  became a curve.
- A rectangle operator with an extra close operator gives one curve.
- Corner classification uses the bounds of the nearest rectangle, and a rectangle
  keeps a side of very small length.

## [1.6.0] - 2026-08-13

### Fixed
- A table gets its rows and columns from the positions of every cell in the
  table, and an empty slot holds a `nil` cell. A table with a gap lost between
  one and three columns, so its shape did not agree with the source.
- Word grouping keeps the source order of glyphs that overlap. A blank glyph at
  the same start position as a letter split the word.

## [1.5.0] - 2026-08-13

### Fixed
- A glyph with no Unicode value falls back to the font character code. Such a
  glyph gave no text.
- A transformed glyph gets its box from the font descent, the em height, the
  advance, and the text matrix. A rotated glyph took a box that did not hold it,
  so it fell outside the table cell that holds it.
- Page rotation applies before the text direction is classified.
- Cell text keeps the extraction order of the words, then orders by line and by
  x position. A multi-line cell reads by line.
- Cell text uses the characters of a word only when the word is inside the cell
  or the cell holds no word. A word that crossed a cell in x alone added text
  from another line.
- Mixed-orientation content groups into lines by the top coordinate.

## [1.4.0] - 2026-08-13

### Fixed
- Character coordinates are normalized for page rotation and for content
  rotation. A rotated page gave no text at all, and a rotated table cell gave
  one line for each character.
- A character is upright only when the text orientation is positive and the text
  basis is horizontal. A quarter-turn matrix counted as upright.
- Duplicate character removal groups characters by the upright flag, the text,
  and the extra attributes, then clusters `doctop` and `x0` independently within
  a tolerance of 1 point and keeps the first character in position order.
- Text extraction follows the line direction and the character direction, and
  sorts on both coordinates.

## [1.3.0] - 2026-08-13

### Fixed
- Each edge snaps to the mean of the coordinate cluster that holds it. An edge
  could take the position of a different cluster, which moved it away from the
  table it belongs to.
- Cells join into one table only when they share a corner. Cells that were only
  near each other joined, which merged tables that are separate.
- Coordinate clustering allows for the difference between the PDFBox and
  pdfminer coordinate sources. PDFBox reads a coordinate as a 32-bit float, so a
  gap that is exactly at the snap tolerance can measure fractionally wider.

## [1.2.0] - 2026-08-13

### Fixed
- A connected region of one cell is not a table. `extract-tables` and
  `find-tables` returned every connected region, so an isolated ruled box became
  a table. A page of vector artwork with no table produced tables.

## [1.1.0] - 2026-08-13

### Fixed
- Path objects are classified per subpath. A subpath of two points is one `:line`,
  a closed axis-aligned subpath of four segments is one `:rect`, and every other
  painted subpath is one `:curve`. A multi-segment polyline became one `:line` per
  segment before, and a bezier became one `:curve` per command.
- Curve points hold the end point of each path command. Bezier control points are
  not path points, and a closed subpath ends at its start point.
- Table edges are derived from curve points, so a table drawn as a polyline is
  found. Strict line detection still uses `:line` objects alone.
- `:edge-min-length-prefilter` is a minimum length in points, default 1, applied
  to the raw edges before snapping. It was a switch that reused
  `:edge-min-length`, so short edges reached table detection and produced tables
  on pages of vector artwork. A boolean is still accepted: `false` means 0 and
  `true` means 1.

### Changed
- Object extraction ignores table options. The object list no longer depends on
  `:vertical-strategy` or `:horizontal-strategy`.

## [1.0.2] - 2026-08-13

### Fixed
- Ruled edges used MediaBox-relative coordinates while words used CropBox-relative
  coordinates. A page whose CropBox origin differs from its MediaBox origin lost
  table cells.
- A rectangle drawn as a closed axis-aligned path became four `:line` objects
  instead of one `:rect`. The misclassified edges produced false table cells, and
  `:lines-strict` could not filter them.

### Added
- The `parity` workflow compares rects, lines, curves, images, and annots against
  Python pdfplumber, per page, by count and bounding box.

## [1.0.1] - 2026-08-13

### Fixed
- Table grid detection accepted a cell when four separate edges happened to cover
  its corners, with no single edge spanning a side. Two tables side by side on a
  page merged into one grid through a phantom column in the gap between them.
- A cell took the words from a band taller than its own row, so each cell held the
  text of the row below it as well.
- Ruled edges on a rotated page did not map to the text coordinate space.
- Ruled edges kept PDF graphics x coordinates while words used coordinates
  relative to the MediaBox origin. A page whose MediaBox does not start at x 0
  lost most of its cells.

### Added
- The `parity` workflow compares table cell content against Python pdfplumber per
  page, and reports the files with low recall.

## [1.0.0] - 2026-08-05
First stable release. The API and the returned data shapes are settled; breaking
changes to either now require a major version bump. No breaking changes from
`0.6.0`: upgrading is a version bump.

### Added
- Password support for opening encrypted PDFs. `open-pdf` takes an optional
  `{:password "..."}` options map, and `with-pdf` accepts it as a third binding
  element: `(with-pdf [doc "statement.pdf" {:password "hunter2"}] ...)`. Both
  the user password and the owner password open a protected document. A missing
  or incorrect password still throws `:pdfplumber/error :encrypted-pdf`.
- A weekly `parity` workflow that measures this library against Python
  pdfplumber over the upstream corpus, so the parity claim is verified by CI
  rather than asserted in the README.

### Fixed
- `open-pdf` carries its `^PDDocument` return type hint on every arity again, so
  `(with-open [d (open-pdf ...)] ...)` no longer reflects on `.close`.

## [0.6.0] - 2026-07-17
### Added
- First-class AcroForm access through `form-fields`, with field name, partial name, type, value, default, options, required/read-only flags, text multiline/max length, and first-widget page plus top-left bbox, and `field-values` for a name-to-value map.
- `outline` returns the nested document outline and bookmarks with resolved 1-based page numbers.
- `attachments` returns embedded files with name, description, size, MIME type, and dates, plus decoded `:bytes` with `:include-data? true`.
- `permissions` returns encryption state and access-permission flags for printing, modification, extraction, assembly, form filling, and annotation, plus key length and security handler.
- `signatures` and `signed?` expose digital-signature metadata plus a `:covers-whole-document?` integrity signal. They do not perform cryptographic, certificate, or trust validation.

## [0.5.0] - 2026-07-17
### Added
- Reducible, single-pass, page-at-a-time object streams through `reducible-chars`, `reducible-words`, `reducible-objects`, `reducible-lines`, `reducible-rects`, `reducible-curves`, `reducible-images`, `reducible-annots`, and generic `page-reducible`. These implement `IReduceInit`, so `transduce`, `into`, and `eduction` short-circuit (for example, with `(take n)`) without extracting later pages.
- `table->maps` produces header-keyed row maps from an extracted table or raw rows, providing a zero-dependency seam for `tech.ml.dataset` or `tablecloth` `->dataset`.

## [0.4.0] - 2026-07-17
### Added
- Visual debugging with `to-image`, backed by PDFBox `PDFRenderer`, plus `draw-line`, `draw-vline`, `draw-hline`, `draw-rect`, `draw-rects`, `draw-circle`, `draw-circles`, `outline-words`, `outline-chars`, `debug-tablefinder`, `reset`, `copy`, `save`, and `show` in `pdfplumber.image`.
- Tagged-PDF logical structure extraction through `structure-tree` and 1-based `page-structure-tree`; untagged PDFs return `[]`.
- AcroForm field names, values, and types on Widget annotation records from `annots`.
- `pdfplumber.cli` command-line PDF object dumps in CSV or JSON, with page/type selection, numeric precision, and JSON indentation options.

### Changed
- `org.clojure/data.json` is now a runtime dependency.

## [0.3.0] - 2026-07-16
### Added
- Feature-parity pass with Python pdfplumber over PDFBox. All additions are backward compatible.
- Full char/graphics-object records: bottom-origin coordinates (`y0`/`y1`), `doctop`, `width`/`height`, `object_type`, char `adv`/`upright`/`matrix`, and graphics `linewidth`/`stroking_color`/`non_stroking_color`.
- Advanced text/word extraction: `extract-text` (layout, keep-blank-chars, use-text-flow), `extract-words` (keep-blank-chars, horizontal-ltr, extra-attrs, split-at-punctuation, expand-ligatures), and `word-map`/`text-map`.
- Composable derived-page views: `crop` (relative/absolute with partial-object clipping), `within-bbox`, `outside-bbox`, and predicate `filter-page`/`filter`.
- Typed object collections (`lines`, `rects`, `curves`, `objects-by-type`) and normalized `edges`/`horizontal-edges`/`vertical-edges`.
- Complete table settings (axis-specific snap/join/intersection tolerances, edge prefilter, forwarded text settings, text-strategy alignment) and a `find-tables`/`find-table` object model with rows/columns/cells/bbox/extract.
- `extract-text-lines`, positional `search` (regex or literal with bounding boxes and contributing chars), `dedupe-chars`, `extract-text-simple`.
- Complete page boxes (mediabox/cropbox/bbox with nonzero origins) and `annots`/`hyperlinks`.

## [0.2.0] - 2026-07-16
### Added
- Multi-table detection with independent vertical and horizontal strategies (`:lines`, `:lines-strict`, `:text`, or `:explicit`), explicit line lists, and configurable snap, join, edge, intersection, and minimum-word tolerances.
- Image extraction through `images` and `:image` entries in `objects`, including bounds, pixel dimensions, color metadata, masks, and optional decoded PNG bytes.

### Changed
- `extract-tables` now returns every independent table region instead of at most one table; `extract-table` retains single-table semantics by returning the first detected table.

## [0.1.3] - 2026-07-09
### Fixed
- POM now includes the project description, homepage URL, and full SCM connection metadata, so Clojars shows a description/homepage and cljdoc has complete source-link data.
