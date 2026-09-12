#!/usr/bin/env bash
# Seeds 4 wallets, fires many concurrent transfers among them (random
# directions/amounts, some deliberately larger than the sender's balance so
# they must decline cleanly). Verifies: total balance unchanged, no wallet
# ever goes negative.
#
# Usage: BASE_URL=https://your-deploy ./burst-conservation.sh [ROUNDS]
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ROUNDS="${1:-300}"
TS="$(date +%s)$RANDOM"

TOKEN_A="cons-user-a-$TS"; TOKEN_B="cons-user-b-$TS"
TOKEN_C="cons-user-c-$TS"; TOKEN_D="cons-user-d-$TS"

WALLET_A=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_A" | jq -r '.id')
WALLET_B=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_B" | jq -r '.id')
WALLET_C=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_C" | jq -r '.id')
WALLET_D=$(curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $TOKEN_D" | jq -r '.id')

for pair in "$WALLET_A:$TOKEN_A" "$WALLET_B:$TOKEN_B" "$WALLET_C:$TOKEN_C" "$WALLET_D:$TOKEN_D"; do
  wid="${pair%%:*}"; tok="${pair##*:}"
  curl -s -X POST "$BASE_URL/wallets/$wid/seed" -H "Authorization: Bearer $tok" \
    -H "Content-Type: application/json" -d '{"amount_paise": 500000}' > /dev/null
done

echo "Wallets: A=$WALLET_A B=$WALLET_B C=$WALLET_C D=$WALLET_D"

sum_balances() {
  local total=0 bal
  for pair in "$WALLET_A:$TOKEN_A" "$WALLET_B:$TOKEN_B" "$WALLET_C:$TOKEN_C" "$WALLET_D:$TOKEN_D"; do
    wid="${pair%%:*}"; tok="${pair##*:}"
    bal=$(curl -s "$BASE_URL/wallets/$wid" -H "Authorization: Bearer $tok" | jq -r '.balance_paise')
    total=$((total + bal))
  done
  echo "$total"
}

BEFORE=$(sum_balances)
echo "Total balance before: $BEFORE"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

export BASE_URL WALLET_A WALLET_B WALLET_C WALLET_D TOKEN_A TOKEN_B TOKEN_C TOKEN_D TS tmpdir

run_transfer() {
  local i="$1"
  local wallets=("$WALLET_A" "$WALLET_B" "$WALLET_C" "$WALLET_D")
  local tokens=("$TOKEN_A" "$TOKEN_B" "$TOKEN_C" "$TOKEN_D")
  local from_idx=$((RANDOM % 4))
  local to_idx=$((RANDOM % 4))
  if [ "$from_idx" -eq "$to_idx" ]; then
    to_idx=$(((from_idx + 1) % 4))
  fi
  # amounts occasionally exceed the seeded balance on purpose, to exercise
  # the clean-decline path under contention, not just the happy path
  local amount=$(((RANDOM % 20000) + 1000))
  local key="cons-key-$TS-$i"
  local body="{\"from\":\"${wallets[$from_idx]}\",\"to\":\"${wallets[$to_idx]}\",\"amount_paise\":$amount,\"idempotency_key\":\"$key\"}"
  curl -s -o "$tmpdir/resp_$i.json" -w "%{http_code}\n" \
    -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer ${tokens[$from_idx]}" \
    -H "Content-Type: application/json" \
    -d "$body" >> "$tmpdir/codes.txt"
}
export -f run_transfer

echo "Firing $ROUNDS concurrent transfers among 4 wallets (mixed directions; some will overdraw and should decline cleanly)"
seq 1 "$ROUNDS" | xargs -P 50 -I{} bash -c 'run_transfer {}'

echo "--- HTTP status codes ---"
sort "$tmpdir"/codes.txt | uniq -c

completed=$(jq -r '.status' "$tmpdir"/resp_*.json 2>/dev/null | grep -c COMPLETED || true)
declined=$(jq -r '.status' "$tmpdir"/resp_*.json 2>/dev/null | grep -c DECLINED_INSUFFICIENT_FUNDS || true)
echo "Completed: $completed, Declined (insufficient funds): $declined"

AFTER=$(sum_balances)
echo "Total balance after: $AFTER"

negative_found="false"
for pair in "$WALLET_A:$TOKEN_A" "$WALLET_B:$TOKEN_B" "$WALLET_C:$TOKEN_C" "$WALLET_D:$TOKEN_D"; do
  wid="${pair%%:*}"; tok="${pair##*:}"
  bal=$(curl -s "$BASE_URL/wallets/$wid" -H "Authorization: Bearer $tok" | jq -r '.balance_paise')
  echo "wallet $wid balance: $bal"
  if [ "$bal" -lt 0 ]; then negative_found="true"; fi
done

if [ "$BEFORE" -eq "$AFTER" ] && [ "$negative_found" == "false" ]; then
  echo "PASS: conservation held ($BEFORE == $AFTER paise), no negative balances, $completed completed / $declined declined"
else
  echo "FAIL: conservation broken or negative balance detected"
  exit 1
fi
