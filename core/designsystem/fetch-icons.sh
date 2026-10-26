#!/bin/sh
# heycam: fetch the Material Symbols Rounded icons (weight 300, 24 px, Apache-2.0) used by HeycamIcons into
# src/main/res/drawable as ic_sym_<name>.xml. Pinned to one google/material-design-icons commit.
# The files are kept as published (including upstream autoMirrored flags for right-to-left layouts); only the theme
# tint is dropped and the fill made black, because Compose's Icon applies its own tint.
#
# The repository's Apache-2.0 LICENSE goes to src/main/assets/licences/ for the in-app licence screen.
#
# Usage (from the repository root): sh core/designsystem/fetch-icons.sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/src/main/res/drawable"
LIC_DIR="$HERE/src/main/assets/licences"
COMMIT=737e3324305806514d7909874fa1818ae1808232   # 2026-10-02 "Update Symbols"
RAW=https://raw.githubusercontent.com/google/material-design-icons/$COMMIT/symbols/android
mkdir -p "$OUT" "$LIC_DIR"
curl -fsSL --retry 3 -o "$LIC_DIR/material-symbols-LICENSE.txt" "https://raw.githubusercontent.com/google/material-design-icons/$COMMIT/LICENSE"
grep -q 'Apache License' "$LIC_DIR/material-symbols-LICENSE.txt" || { echo "unexpected licence file" >&2; exit 1; }

for name in add arrow_back arrow_downward arrow_upward check chevron_right close content_copy \
    create_new_folder delete description document_scanner download drag_indicator drive_file_move edit error \
    folder grid_view help image info keep language more_vert open_in_new photo_camera picture_as_pdf print \
    refresh restore_from_trash rotate_right search settings share sort text_snippet title tune undo \
    verified_user warning wifi_off; do
  tmp="$OUT/ic_sym_$name.xml.part"
  curl -fsSL --retry 3 -o "$tmp" "$RAW/$name/materialsymbolsrounded/${name}_wght300_24px.xml"
  grep -q 'android:pathData=' "$tmp" || { echo "no path data in $name" >&2; rm -f "$tmp"; exit 1; }
  perl -0pe 's/\s*android:tint="[^"]*"//; s/\@android:color\/white/\@android:color\/black/g' "$tmp" > "$OUT/ic_sym_$name.xml"
  rm -f "$tmp"
  echo "ok  ic_sym_$name.xml"
done
