# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

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
