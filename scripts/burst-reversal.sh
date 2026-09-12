#!/usr/bin/env bash
# Fires the same reversal concurrently. Expect one completed reversal and
# identical results for retries, with balances restored to their pre-transfer values.
#
# Usage: BASE_URL=https://your-deploy ./burst-reversal.sh [K]
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
K="${1:-2}"
TS="$(date +%s)$RANDOM"
TOKEN_A="reverse-user-a-$TS"
TOKEN_B="reverse-user-b-$TS"

WALLET_A=$(curl -sS -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_A" | jq -r '.id')
WALLET_B=$(curl -sS -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_B" | jq -r '.id')
curl -sS -X POST "$BASE_URL/wallets/$WALLET_A/seed" \
  -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"amount_paise":1000}' >/dev/null

TRANSFER=$(curl -sS -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d "{\"from\":\"$WALLET_A\",\"to\":\"$WALLET_B\",\"amount_paise\":100,\"idempotency_key\":\"reverse-transfer-$TS\"}")
TRANSFER_ID=$(printf '%s' "$TRANSFER" | jq -r '.id')
REVERSAL_KEY="reverse-key-$TS"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

reverse_once() {
  local i="$1"
  curl -sS -o "$tmpdir/response_$i.json" -w '%{http_code}\n' \
    -X POST "$BASE_URL/transfers/$TRANSFER_ID/reverse" \
    -H "Authorization: Bearer $TOKEN_A" \
    -H 'Content-Type: application/json' \
    -d "{\"idempotency_key\":\"$REVERSAL_KEY\"}" >> "$tmpdir/codes.txt"
}
export -f reverse_once
export BASE_URL TOKEN_A TRANSFER_ID REVERSAL_KEY tmpdir

seq 1 "$K" | xargs -P "$K" -I{} bash -c 'reverse_once {}'

BALANCE_A=$(curl -sS "$BASE_URL/wallets/$WALLET_A" -H "Authorization: Bearer $TOKEN_A" | jq -r '.balance_paise')
BALANCE_B=$(curl -sS "$BASE_URL/wallets/$WALLET_B" -H "Authorization: Bearer $TOKEN_B" | jq -r '.balance_paise')
IDS=$(jq -r '.id' "$tmpdir"/response_*.json | sort -u)
STATUSES=$(jq -r '.status' "$tmpdir"/response_*.json | sort -u)

cat "$tmpdir/codes.txt" | sort | uniq -c
echo "Distinct reversal IDs: $IDS"
echo "Statuses: $STATUSES"
echo "Final balance A=$BALANCE_A (expected 1000), B=$BALANCE_B (expected 0)"

if [ "$(printf '%s\n' "$IDS" | grep -c .)" -eq 1 ] \
  && [ "$BALANCE_A" -eq 1000 ] && [ "$BALANCE_B" -eq 0 ]; then
  echo "PASS: reversal was applied exactly once under $K concurrent requests"
else
  echo "FAIL: reversal idempotency or balance restoration failed"
  exit 1
fi
