# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Document lifecycle: `open-pdf` (path / `File` / `byte[]` / `InputStream`) and
  the `with-pdf` macro, with an `ex-info` error model (`:invalid-input`,
  `:encrypted-pdf`, `:parse-failed`, `:page-not-found`).
- `metadata`, `pages`, and `page` (1-based) APIs.
- Text extraction: `chars`, `words`, and `text`, with `:page`, `:bbox`,
  `:x-tolerance`, and `:y-tolerance` options.
- Object extraction: `objects` (lines, rectangles, curves), with line
  `:orientation` classification and `:page`/`:types`/`:bbox` options.
- `pdfplumber.geometry`: bbox helpers and PDFBox↔top-left coordinate conversion.

[Unreleased]: https://github.com/jsavyasachi/pdfplumber-clj/commits/main
