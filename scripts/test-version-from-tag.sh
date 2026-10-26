#!/usr/bin/env bash
# Checks scripts/version-from-tag.sh against known tags. Run by CI; prints nothing but failures and a summary.
set -euo pipefail
script="$(dirname "$0")/version-from-tag.sh"
failures=0

expect_ok() { # tag, expected name, expected code
  local out
  if ! out=$("$script" "$1" 2>&1) || [[ "$out" != "version_name=$2"$'\n'"version_code=$3" ]]; then
    echo "FAIL $1: got '$out'"
    failures=$((failures + 1))
  fi
}

expect_error() { # tag
  if "$script" "$1" >/dev/null 2>&1; then
    echo "FAIL $1: accepted"
    failures=$((failures + 1))
  fi
}

expect_ok v0.1.0 0.1.0 10000
expect_ok v0.9.3 0.9.3 90300
expect_ok v1.0.0 1.0.0 1000000
expect_ok v1.3.1 1.3.1 1030100
expect_ok v1.99.99 1.99.99 1999900
expect_ok v2100.0.0 2100.0.0 2100000000
expect_error v0.0.0
expect_error v1.03.1
expect_error v1.100.0
expect_error 1.2.3
expect_error v1.2.3-rc.1
expect_error v1.2
expect_error "v1.2.3 "
expect_error v10000.0.0

if ((failures)); then
  echo "$failures version-from-tag case(s) failed"
  exit 1
fi
echo "version-from-tag: all cases pass"
