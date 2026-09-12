#!/usr/bin/env bash
# Fires N concurrent POST /wallets for a brand-new user id.
# Expect: exactly one distinct wallet id across all responses.
#
# Usage: BASE_URL=https://your-deploy ./burst-get-or-create.sh [N]
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
N="${1:-50}"
USER_ID="burst-user-$(date +%s)-$RANDOM"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

export BASE_URL USER_ID tmpdir

fire_once() {
  local i="$1"
  curl -s -o "$tmpdir/resp_$i.json" -w "%{http_code}\n" \
    -X POST "$BASE_URL/wallets" \
    -H "Authorization: Bearer $USER_ID" \
    -H "Content-Type: application/json" >> "$tmpdir/codes.txt"
}
export -f fire_once

echo "Firing $N concurrent POST /wallets for user=$USER_ID against $BASE_URL"
seq 1 "$N" | xargs -P "$N" -I{} bash -c 'fire_once {}'

echo "--- HTTP status codes ---"
sort "$tmpdir"/codes.txt | uniq -c

echo "--- Distinct wallet ids returned ---"
distinct_ids=$(jq -r '.id' "$tmpdir"/resp_*.json | sort -u)
echo "$distinct_ids"
count=$(echo "$distinct_ids" | grep -c . || true)

if [ "$count" -eq 1 ]; then
  echo "PASS: exactly one wallet id returned across $N concurrent requests"
else
  echo "FAIL: $count distinct wallet ids returned (expected 1)"
  exit 1
fi
