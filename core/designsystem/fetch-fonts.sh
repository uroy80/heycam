#!/bin/sh
# heycam: fetch the three OFL font families into this module, pinned to google/fonts commits,
# verified by git blob hash (the "sha" the GitHub contents API reports), renamed to res/font rules
# (lowercase a-z, 0-9, _), with each family's OFL.txt copied to assets/licences/ for the in-app licence screen.
#
# Usage (from the repository root): sh core/designsystem/fetch-fonts.sh
# Needs: curl, git (for git hash-object). Downloads ~1.24 MB. Files are shipped UNMODIFIED
# (IBM Plex Mono carries Reserved Font Name "Plex": do not subset/convert it under that name).
set -eu
MOD=$(cd "$(dirname "$0")" && pwd)
FONT_DIR="$MOD/src/main/res/font"
LIC_DIR="$MOD/src/main/assets/licences"
mkdir -p "$FONT_DIR" "$LIC_DIR"
RAW=https://raw.githubusercontent.com/google/fonts

fetch() { # commit path dest blobsha
  url="$RAW/$1/$2"
  tmp="$3.part"
  curl -fsSL --retry 3 -o "$tmp" "$url"
  got=$(git hash-object "$tmp")
  if [ "$got" != "$4" ]; then echo "hash mismatch for $2: $got != $4" >&2; rm -f "$tmp"; exit 1; fi
  mv "$tmp" "$3"
  echo "ok  $3"
}

F=4024282d9b0cffcdb8e3024560862746178d741f   # ofl/fraunces
L=e2332cf862ac3145c0ee5f24f04f4c1819b2410b   # ofl/librefranklin
M=01d5d9f05246fb7090a8403b81257535cb1a088b   # ofl/ibmplexmono

fetch $F 'ofl/fraunces/Fraunces%5BSOFT,WONK,opsz,wght%5D.ttf'        "$FONT_DIR/fraunces.ttf"              8210f9488d3c732359a9292dd09aca3f2bae830e
fetch $F 'ofl/fraunces/Fraunces-Italic%5BSOFT,WONK,opsz,wght%5D.ttf' "$FONT_DIR/fraunces_italic.ttf"       2ddf59a11b89a3d6b2816ea6ae7965026c08143a
fetch $L 'ofl/librefranklin/LibreFranklin%5Bwght%5D.ttf'              "$FONT_DIR/libre_franklin.ttf"        8cf5549196f5e98dc27be05fc2b3691e77eaa89c
fetch $M 'ofl/ibmplexmono/IBMPlexMono-Regular.ttf'                    "$FONT_DIR/ibm_plex_mono_regular.ttf" 0c9770d5183ba60dc4350d3e011b782a320761ae
fetch $M 'ofl/ibmplexmono/IBMPlexMono-Medium.ttf'                     "$FONT_DIR/ibm_plex_mono_medium.ttf"  33c546f68b6007a45a3f10e845523abb2db25399

fetch $F 'ofl/fraunces/OFL.txt'      "$LIC_DIR/fraunces-OFL.txt"       22fa7ce5733434d0c634f6e55116f410858e938c
fetch $L 'ofl/librefranklin/OFL.txt' "$LIC_DIR/libre-franklin-OFL.txt" 04cef395b1c1643f71dae8451a96fa7c073301d4
fetch $M 'ofl/ibmplexmono/OFL.txt'   "$LIC_DIR/ibm-plex-mono-OFL.txt"  c35c4c618fab33da8695177b3a6cefe0810b7b28
