#!/usr/bin/env bash
# Fires the same transfer (same idempotency_key, same body) K times
# concurrently. Expect: exactly one debit + one credit, all responses
# carry the same transfer id/status.
#
# Usage: BASE_URL=https://your-deploy ./burst-idempotent-retry.sh [K]
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
K="${1:-30}"
TS="$(date +%s)$RANDOM"

TOKEN_A="idem-user-a-$TS"
TOKEN_B="idem-user-b-$TS"

WALLET_A=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_A" | jq -r '.id')
WALLET_B=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_B" | jq -r '.id')
echo "wallet A=$WALLET_A wallet B=$WALLET_B"

curl -s -X POST "$BASE_URL/wallets/$WALLET_A/seed" -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" -d '{"amount_paise": 100000}' > /dev/null

IDEMP_KEY="idem-key-$TS"
BODY="{\"from\":\"$WALLET_A\",\"to\":\"$WALLET_B\",\"amount_paise\":1000,\"idempotency_key\":\"$IDEMP_KEY\"}"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

export BASE_URL TOKEN_A BODY tmpdir

fire_once() {
  local i="$1"
  curl -s -o "$tmpdir/resp_$i.json" -w "%{http_code}\n" \
    -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer $TOKEN_A" \
    -H "Content-Type: application/json" \
    -d "$BODY" >> "$tmpdir/codes.txt"
}
export -f fire_once

echo "Firing $K concurrent identical POST /transfers with idempotency_key=$IDEMP_KEY"
seq 1 "$K" | xargs -P "$K" -I{} bash -c 'fire_once {}'

echo "--- HTTP status codes ---"
sort "$tmpdir"/codes.txt | uniq -c

echo "--- Distinct transfer ids returned ---"
distinct_ids=$(jq -r '.id' "$tmpdir"/resp_*.json | sort -u)
echo "$distinct_ids"
count=$(echo "$distinct_ids" | grep -c . || true)

BALANCE_A=$(curl -s "$BASE_URL/wallets/$WALLET_A" -H "Authorization: Bearer $TOKEN_A" | jq -r '.balance_paise')
BALANCE_B=$(curl -s "$BASE_URL/wallets/$WALLET_B" -H "Authorization: Bearer $TOKEN_B" | jq -r '.balance_paise')
echo "Final balance A=$BALANCE_A (expected 99000), B=$BALANCE_B (expected 1000)"

if [ "$count" -eq 1 ] && [ "$BALANCE_A" -eq 99000 ] && [ "$BALANCE_B" -eq 1000 ]; then
  echo "PASS: exactly-once transfer under $K-way concurrent retry storm"
else
  echo "FAIL: expected a single transfer id and exact balances, got $count distinct ids, A=$BALANCE_A, B=$BALANCE_B"
  exit 1
fi
