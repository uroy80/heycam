#!/usr/bin/env bash
# Gate G2 (PLAN 6.5, docs/pdf-compat.txt): the searchable PDFs that :core:pdf's PdfCompatFixturesTest writes are
# valid (qpdf --check) and their text layer reads back (pdftotext finds every line expected.txt lists for the file).
#
#   ./gradlew :core:pdf:testDebugUnitTest --tests '*PdfCompatFixturesTest*' --rerun
#   scripts/check-pdf-fixtures.sh [fixtures directory, default core/pdf/build/pdf-fixtures]
#
# Needs qpdf and poppler's pdftotext. expected.txt holds "<file><TAB><text>" lines.
set -euo pipefail

dir="${1:-core/pdf/build/pdf-fixtures}"
expected="$dir/expected.txt"
min_lines=15 # 6 fixtures, 15 lines (latin 3, hindi 3, cjk 3, rotated 2, tilted 3, non-bmp 1)

if [[ ! -f "$expected" ]]; then
  echo "::error::$expected is missing. Run :core:pdf:testDebugUnitTest (PdfCompatFixturesTest) first." >&2
  exit 1
fi
lines="$(grep -c . "$expected" || true)"
if ((lines < min_lines)); then
  echo "::error::$expected has $lines lines; PdfCompatFixturesTest writes at least $min_lines." >&2
  exit 1
fi

failed=0
while IFS= read -r file; do
  if ! report="$(qpdf --check "$dir/$file" 2>&1)"; then
    echo "::error::qpdf --check $file"
    echo "$report"
    failed=1
  fi
done < <(cut -f1 "$expected" | sort -u)

while IFS=$'\t' read -r file text; do
  # Read the whole text first: grep -q stopping early would give pdftotext a SIGPIPE under pipefail.
  if ! layer="$(pdftotext -enc UTF-8 "$dir/$file" -)"; then
    echo "::error::pdftotext could not read $file"
    failed=1
  elif ! grep -qF -- "$text" <<<"$layer"; then
    echo "::error::pdftotext did not find \"$text\" in $file"
    failed=1
  fi
done <"$expected"

if ((failed == 0)); then
  files="$(cut -f1 "$expected" | sort -u | wc -l | tr -d ' ')"
  echo "PDF fixtures: $files files pass qpdf --check; pdftotext finds all $lines lines."
fi
exit "$failed"
