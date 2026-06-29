#!/usr/bin/env python3
"""Generate parity golden from Python pdfplumber over a corpus directory.

Emits JSON {filename: {pages, words, text, error}} for each *.pdf. Used by the
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
for path in sorted(glob.glob(os.path.join(corpus, "*.pdf"))):
    name = os.path.basename(path)
    try:
        with pdfplumber.open(path) as pdf:
            texts, words = [], 0
            for page in pdf.pages:
                texts.append(page.extract_text() or "")
                words += len(page.extract_words())
            result[name] = {
                "pages": len(pdf.pages),
                "words": words,
                "text": "\n".join(texts),
                "error": None,
            }
    except Exception as e:  # noqa: BLE001 - record, don't abort the run
        result[name] = {"error": f"{type(e).__name__}: {str(e)[:200]}"}

with open(out, "w") as f:
    json.dump(result, f)

ok = sum(1 for v in result.values() if v.get("error") is None)
print(f"golden: {len(result)} entries ({ok} ok, {len(result) - ok} errored) -> {out}")
