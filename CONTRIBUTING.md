# Contributing to pdfplumber-clj

Thanks for your interest in improving `pdfplumber-clj`. Bug reports, fixes, and
focused feature contributions are all welcome.

## Before you start

- For anything beyond a trivial fix, **open an issue first** so we can agree on
  the approach before you invest time.
- Check existing issues and pull requests to avoid duplicate work.

## Project layout

A single `deps.edn` library. Source under `src/pdfplumber/`:

| Namespace | Purpose |
|---|---|
| `pdfplumber.core` | public API + `with-pdf` lifecycle |
| `pdfplumber.document` | load, metadata, page enumeration, error model |
| `pdfplumber.text` | character / word / text extraction |
| `pdfplumber.geometry` | bbox math + PDFBox↔public coordinate conversion |

Public coordinates use a **top-left origin** with bbox `[x0 top x1 bottom]`;
PDFBox's bottom-left coordinates are converted only in `pdfplumber.geometry`.
Page numbers are **1-based**. Expected failures throw `ex-info` with a
`:pdfplumber/error` key.

## Building and testing

Requires JDK 17+.

```bash
clojure -M:test            # full suite (Kaocha)
clojure -M:1.11:test       # Clojure 1.11 matrix cell
clojure -M:1.12:test       # Clojure 1.12 matrix cell
clojure -T:build jar       # build a jar
```

Optional real-world / parity corpus (nothing is committed):

```bash
dev/fetch-corpus.sh        # downloads the jsvine/pdfplumber test PDFs into corpus/
```

The bar for a mergeable change:

- **Tests first.** Add or update tests for the behavior you change; for a bug
  fix, include a regression test that fails before your fix and passes after.
- **Green build.** `clojure -M:test` passes and `src` compiles with **zero**
  reflection warnings (`*warn-on-reflection*` is on).
- **No scope creep.** Keep each pull request to one logical change.

## Commits and pull requests

- Follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:` …).
- Keep the subject in the imperative mood and under ~72 characters.
- Update `CHANGES.md` when your change is user-visible.
- Rebase on the latest `main` before opening the pull request.

## License

By contributing, you agree that your contributions will be licensed under the
Eclipse Public License 2.0, the same license as this project (see `LICENSE`).
