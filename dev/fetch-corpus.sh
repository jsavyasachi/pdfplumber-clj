#!/usr/bin/env bash
# Download real-world test PDFs into a gitignored corpus/ directory for opt-in
# integration / parity testing. Nothing here is committed (licensing); fetch on
# demand locally or in CI.
#
# Usage: dev/fetch-corpus.sh [dest-dir]   (default: corpus/pdfplumber)
set -euo pipefail

dest="${1:-corpus/pdfplumber}"
mkdir -p "$dest"

echo "Fetching jsvine/pdfplumber tests/pdfs -> $dest"
gh api repos/jsvine/pdfplumber/contents/tests/pdfs --paginate \
  -q '.[] | select(.type=="file") | .download_url' \
| while read -r url; do
    name="$(basename "$url")"
    if [ ! -f "$dest/$name" ]; then
      curl -fsSL "$url" -o "$dest/$name"
    fi
  done

echo "Done: $(find "$dest" -maxdepth 1 -type f | wc -l | tr -d ' ') files in $dest"
