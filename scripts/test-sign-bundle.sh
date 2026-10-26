#!/usr/bin/env bash
# Tests scripts/sign-bundle.sh with throwaway keys: an RSA and an EC upload key both sign and verify, with the
# matching algorithm; a DSA key, a missing alias and an empty password are refused. Needs a JDK.
#
#   scripts/test-sign-bundle.sh
set -euo pipefail
cd "$(dirname "$0")/.."

bin="${JAVA_HOME:+$JAVA_HOME/bin/}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export UPLOAD_KEYSTORE_PASSWORD=test-only-password
failed=0
pass() { echo "ok    $*"; }
fail() {
  echo "FAIL  $*"
  failed=1
}

bundle() { # a small zip standing in for an .aab
  mkdir -p "$tmp/content/base"
  echo "heycam" >"$tmp/content/base/file.txt"
  (cd "$tmp/content" && "${bin}jar" --create --file "$1" base/file.txt)
}

key() { # keystore, alias, keytool -keyalg arguments...
  local store="$1" name="$2"
  shift 2
  "${bin}keytool" -genkeypair -keystore "$store" -storetype PKCS12 -storepass:env UPLOAD_KEYSTORE_PASSWORD \
    -alias "$name" "$@" -dname "CN=heycam test" -validity 2 >/dev/null 2>&1
}

for case in "RSA:-keyalg RSA -keysize 2048:SHA256withRSA" "EC:-keyalg EC -groupname secp256r1:SHA256withECDSA"; do
  IFS=: read -r type args expected <<<"$case"
  store="$tmp/$type.p12"
  aab="$tmp/$type.aab"
  # shellcheck disable=SC2086 # the keytool arguments are split on purpose
  key "$store" upload $args
  bundle "$aab"
  if out="$(scripts/sign-bundle.sh "$aab" "$store" upload 2>&1)" && [[ "$out" == "Signed with $expected"* ]]; then
    pass "$type key: $out"
  else
    fail "$type key: $out"
  fi
  if "${bin}jarsigner" -verify -verbose -certs "$aab" 2>/dev/null | grep -q "CN=heycam test"; then
    pass "$type key: the bundle carries the upload certificate"
  else
    fail "$type key: the bundle does not carry the upload certificate"
  fi
done

key "$tmp/DSA.p12" upload -keyalg DSA -keysize 2048
bundle "$tmp/DSA.aab"
if out="$(scripts/sign-bundle.sh "$tmp/DSA.aab" "$tmp/DSA.p12" upload 2>&1)"; then
  fail "DSA key was accepted: $out"
else
  pass "DSA key refused: $out"
fi

if out="$(scripts/sign-bundle.sh "$tmp/RSA.aab" "$tmp/RSA.p12" missing 2>&1)"; then
  fail "a missing alias was accepted: $out"
else
  pass "a missing alias is refused"
fi

if out="$(UPLOAD_KEYSTORE_PASSWORD='' scripts/sign-bundle.sh "$tmp/RSA.aab" "$tmp/RSA.p12" upload 2>&1)"; then
  fail "an empty password was accepted: $out"
else
  pass "an empty password is refused"
fi

exit "$failed"
