#!/usr/bin/env python3
"""Generate parity golden from Python pdfplumber over a corpus directory.

Emits JSON {filename: {pages, words, text, tables, rects, lines, curves, images, annots,
error}} for each *.pdf. Used by the
opt-in :corpus parity test. Run inside a venv with pdfplumber installed:

    .venv/bin/python dev/gen_golden.py [corpus-dir] [out.json]
"""
import glob
import json
import os
import sys

import pdfplumber

corpus = sys.argv[1] if len(sys.argv) > 1 else "corpus/pdfplumber"
out = sys.argv[2] if len(sys.argv) > 2 else "corpus/golden.json"

result = {}


def object_record(objects):
    return {
        "count": len(objects),
        "boxes": [
            [round(obj[key], 2) for key in ("x0", "top", "x1", "bottom")]
            for obj in objects
        ],
    }


for path in sorted(glob.glob(os.path.join(corpus, "*.pdf"))):
    name = os.path.basename(path)
    try:
        with pdfplumber.open(path) as pdf:
            texts, tables, words = [], [], 0
            page_objects = {kind: [] for kind in ("rects", "lines", "curves", "images", "annots")}
            for page in pdf.pages:
                texts.append(page.extract_text() or "")
                tables.append(page.extract_tables())
                words += len(page.extract_words())
                for kind in page_objects:
                    # An object type that raises must not discard the whole file.
                    # Record null for that page and keep the text and table data.
                    try:
                        page_objects[kind].append(object_record(getattr(page, kind)))
                    except Exception:  # noqa: BLE001
                        page_objects[kind].append(None)
            result[name] = {
                "pages": len(pdf.pages),
                "words": words,
                "text": "\n".join(texts),
                "tables": tables,
                **page_objects,
                "error": None,
            }
    except Exception as e:  # noqa: BLE001 - record, don't abort the run
        result[name] = {"error": f"{type(e).__name__}: {str(e)[:200]}"}

with open(out, "w") as f:
    json.dump(result, f)

ok = sum(1 for v in result.values() if v.get("error") is None)
print(f"golden: {len(result)} entries ({ok} ok, {len(result) - ok} errored) -> {out}")
