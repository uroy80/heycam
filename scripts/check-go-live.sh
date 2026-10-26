#!/usr/bin/env bash
# Go-live check for what the app, the listing and the privacy policy link to (docs/RELEASE-CHECKLIST.txt §3).
# Read-only: it fetches, compares and reports. Run it from a checkout of main after pushing and deploying.
#
#   scripts/check-go-live.sh
#
# - github.com/uroy80/heycam has main at this checkout's main. The policy's history link, the site's Source links,
#   About -> Source code and the listing all point there, so main goes up before the policy does.
# - https://heycam.edgr.in serves docs/ exactly as it is here (the home page, the privacy policy and every file in
#   docs/assets/), each with 200, and the pages with the site's Content-Security-Policy.
# - The policy's "Last updated" date says the same in its datetime and its text, is not in the future, and is not
#   older than the last commit that changed the policy.
set -euo pipefail
cd "$(dirname "$0")/.."

repo=https://github.com/uroy80/heycam
site=https://heycam.edgr.in
policy=docs/privacy/index.html
failed=0
ok() { echo "ok    $*"; }
fail() {
  echo "FAIL  $*"
  failed=1
}

# Source repository
main="$(git rev-parse main)"
remote="$(git ls-remote "$repo" refs/heads/main | cut -f1)"
if [[ -z "$remote" ]]; then
  fail "$repo has no main branch: push main first"
elif [[ "$remote" != "$main" ]]; then
  fail "$repo main is ${remote:0:7}, this checkout's main is ${main:0:7}"
else
  ok "$repo main is ${main:0:7}"
fi

# Site
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
serves() { # path on the site, file in docs/
  local url="$site$1" status
  if ! status="$(curl -sS --max-time 20 -o "$tmp/body" -D "$tmp/headers" -w '%{http_code}' "$url")"; then
    fail "$url: no answer"
  elif [[ "$status" != 200 ]]; then
    fail "$url: HTTP $status"
  elif [[ "$2" == *.html ]] && ! grep -qi "^content-security-policy: default-src 'none';" "$tmp/headers"; then
    fail "$url: no Content-Security-Policy header"
  elif ! cmp -s "$tmp/body" "$2"; then
    fail "$url is not $2 as committed"
  else
    ok "$url"
  fi
}
serves / docs/index.html
serves /privacy/ "$policy"
while IFS= read -r file; do
  serves "/${file#docs/}" "$file"
done < <(find docs/assets -type f ! -name '.*' | sort)

# The policy's date
stamp="$(grep -oE '<time datetime="[0-9]{4}-[0-9]{2}-[0-9]{2}">[0-9]{1,2} [A-Z][a-z]+ [0-9]{4}</time>' "$policy" | head -n 1 || true)"
if [[ -z "$stamp" ]]; then
  fail "$policy has no <time datetime=\"YYYY-MM-DD\">D Month YYYY</time>"
else
  iso="$(sed -E 's/.*datetime="([^"]+)".*/\1/' <<<"$stamp")"
  read -r day month year <<<"$(sed -E 's/.*>([^<]+)<\/time>/\1/' <<<"$stamp")"
  months=(January February March April May June July August September October November December)
  number=""
  for i in "${!months[@]}"; do
    if [[ "${months[$i]}" == "$month" ]]; then number="$(printf '%02d' $((i + 1)))"; fi
  done
  changed="$(git log -1 --format=%cs -- "$policy")"
  if [[ "$iso" != "$year-$number-$(printf '%02d' $((10#$day)))" ]]; then
    fail "$policy: datetime $iso and text \"$day $month $year\" differ"
  elif [[ "$iso" > "$(date +%F)" ]]; then
    fail "$policy: Last updated $iso is in the future"
  elif [[ "$iso" < "$changed" ]]; then
    fail "$policy: Last updated $iso, but the policy changed on $changed"
  else
    ok "$policy: Last updated $iso"
  fi
fi

if ((failed)); then
  echo "Not live yet: fix the lines marked FAIL, then run this again."
else
  echo "Live: the source, the site and the policy match this checkout."
fi
exit "$failed"
