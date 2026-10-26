#!/usr/bin/env bash
# Play versionCode / versionName from a release tag:
#   v1.3.1 -> version_name=1.3.1  version_code=1030100
# versionCode = MAJOR*1,000,000 + MINOR*10,000 + PATCH*100. The last two digits stay 00, free for a rebuild of the
# same version. MINOR and PATCH are 0-99; the code must be 1..2,100,000,000 (Play's maximum).
# Prints GitHub Actions "key=value" lines; CI appends them to "$GITHUB_OUTPUT". Tested by scripts/test-version-from-tag.sh.
set -euo pipefail
tag="${1:?usage: version-from-tag.sh vMAJOR.MINOR.PATCH}"
if [[ ! "$tag" =~ ^v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]]; then
  echo "error: tag '$tag' is not vMAJOR.MINOR.PATCH (MINOR/PATCH 0-99, no leading zeros)" >&2
  exit 1
fi
major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
code=$((major * 1000000 + minor * 10000 + patch * 100))
if ((code < 1 || code > 2100000000)); then
  echo "error: versionCode $code outside 1..2100000000" >&2
  exit 1
fi
echo "version_name=${major}.${minor}.${patch}"
echo "version_code=${code}"
