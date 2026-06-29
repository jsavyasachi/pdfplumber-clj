# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.1] - 2026-06-28

First Clojars release. (0.1.0 was not published: its POM omitted the license.)


### Added
- Document lifecycle: `open-pdf` (path / `File` / `byte[]` / `InputStream`) and
  the `with-pdf` macro, with an `ex-info` error model (`:invalid-input`,
  `:encrypted-pdf`, `:parse-failed`, `:page-not-found`).
- `metadata`, `pages`, and `page` (1-based) APIs.
- Text extraction: `chars`, `words`, and `text`, with `:page`, `:bbox`,
  `:x-tolerance`, and `:y-tolerance` options.
- Object extraction: `objects` (lines, rectangles, curves), with line
  `:orientation` classification and `:page`/`:types`/`:bbox` options.
- `crop-page` page views, accepted by `chars`/`words`/`text`/`objects` in place
  of a document handle to restrict extraction to a bbox.
- Table extraction: `extract-table`/`extract-tables` with `:lines` (grid from
  ruling lines) and `:text` (columns inferred from word alignment) strategies,
  returning `:rows`/`:cells`/`:debug`.
- `pdfplumber.geometry`: bbox helpers and PDFBox↔top-left coordinate conversion.

### Fixed
- Word splitting now breaks on whitespace characters in addition to wide gaps,
  so words separated by a narrow space are no longer merged. Validated against
  the Python pdfplumber corpus (text-similarity and word-count median 1.0).

[0.1.1]: https://github.com/jsavyasachi/pdfplumber-clj/releases/tag/v0.1.1
