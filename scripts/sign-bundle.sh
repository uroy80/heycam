#!/usr/bin/env bash
# Signs an Android App Bundle with the upload key (release.yml's publish job) and checks the signature. The signature
# algorithm follows the key: SHA256withRSA for an RSA upload key, SHA256withECDSA for an EC one (Play accepts both).
#
#   UPLOAD_KEYSTORE_PASSWORD=... scripts/sign-bundle.sh <bundle.aab> <PKCS12 keystore> <key alias>
#
# The password is read from the environment only, so it never appears on a command line. Uses $JAVA_HOME's keytool and
# jarsigner when JAVA_HOME is set.
set -euo pipefail

if (($# != 3)); then
  echo "usage: UPLOAD_KEYSTORE_PASSWORD=... $0 <bundle.aab> <keystore.p12> <alias>" >&2
  exit 2
fi
bundle="$1"
keystore="$2"
alias="$3"
: "${UPLOAD_KEYSTORE_PASSWORD:?UPLOAD_KEYSTORE_PASSWORD is not set}"
bin="${JAVA_HOME:+$JAVA_HOME/bin/}"

# "Subject Public Key Algorithm: 2048-bit RSA key" or "256-bit EC (secp256r1) key"; the first one is the key's own.
listing="$("${bin}keytool" -list -v -keystore "$keystore" -storetype PKCS12 \
  -storepass:env UPLOAD_KEYSTORE_PASSWORD -alias "$alias")"
key="$(sed -n 's/^Subject Public Key Algorithm: //p' <<<"$listing" | head -n 1)"
case "$key" in
  *RSA*) sigalg=SHA256withRSA ;;
  *" EC "* | *"-bit EC"*) sigalg=SHA256withECDSA ;;
  *)
    echo "::error::Upload key \"$alias\" is ${key:-of an unknown type}; use an RSA or EC key." >&2
    exit 1
    ;;
esac

"${bin}jarsigner" -keystore "$keystore" -storetype PKCS12 -storepass:env UPLOAD_KEYSTORE_PASSWORD \
  -sigalg "$sigalg" -digestalg SHA-256 "$bundle" "$alias" >/dev/null
# Plain -verify: -strict rejects the self-signed upload certificate. Read the whole output before matching it.
verified="$("${bin}jarsigner" -verify "$bundle")"
if ! grep -qx 'jar verified.' <<<"$verified"; then
  echo "::error::jarsigner could not verify $bundle after signing" >&2
  echo "$verified" >&2
  exit 1
fi
echo "Signed with $sigalg ($key)."
