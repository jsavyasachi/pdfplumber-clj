# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

## [0.2.0] - 2026-07-16
### Added
- Multi-table detection with independent vertical and horizontal strategies (`:lines`, `:lines-strict`, `:text`, or `:explicit`), explicit line lists, and configurable snap, join, edge, intersection, and minimum-word tolerances.
- Image extraction through `images` and `:image` entries in `objects`, including bounds, pixel dimensions, color metadata, masks, and optional decoded PNG bytes.

### Changed
- `extract-tables` now returns every independent table region instead of at most one table; `extract-table` retains single-table semantics by returning the first detected table.

## [0.1.3] - 2026-07-09
### Fixed
- POM now includes the project description, homepage URL, and full SCM connection metadata, so Clojars shows a description/homepage and cljdoc has complete source-link data.
