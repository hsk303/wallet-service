# Wallet & P2P Transfer — Write-up

## Data model

- `wallets(id UUID pk, user_id unique, balance_paise bigint check >= 0, created_at)`
- `transfers(id UUID pk, idempotency_key unique, from_wallet_id, to_wallet_id, amount_paise check > 0, request_hash, status, created_at)`, with `check (from_wallet_id <> to_wallet_id)`.

Money is always `bigint` paise, never a float or decimal-rupees column — this is enforced by
type, not just convention. `request_hash` is a SHA-256 of `(from, to, amount_paise)`, stored
alongside the row so a same-key/different-body replay can be detected without re-deriving
anything from the original request.

## The simplest-correct mechanism, and what I rejected

For conservation + no-overdraft I locked both wallets involved in a transfer with two explicit
`SELECT ... FOR UPDATE` statements, always requesting the lower wallet id first, then did the
balance check and the two `UPDATE`s in ordinary application code inside those locks. Two explicit
statements are intentional: PostgreSQL may acquire row locks in scan order before applying
`ORDER BY` on a multi-row `SELECT ... FOR UPDATE`.

Rejected alternatives:

- **Read-modify-write in the app** (read balance, subtract, write back) — the textbook lost-update
  race. Two concurrent transfers debiting the same wallet can both read the pre-transfer balance,
  both compute a "sufficient funds" answer from stale data, and both write back — conservation
  breaks immediately under contention. Not viable at any scale of concurrency.
- **`SERIALIZABLE` isolation for the whole transaction** — correct, but it pushes the cost of
  conflict detection onto the client: Postgres aborts one of the two conflicting transactions with
  a serialization failure (`40001`) that the caller must catch and retry. For a two-row worst case
  I already know exactly which two rows are contended, so taking explicit locks on exactly those
  two rows gets the same "only one writer proceeds at a time" guarantee without needing an
  app-level retry loop, and it's easier to reason about because the failure mode ("waits for the
  lock") is simpler than "runs concurrently, might get retroactively aborted."
- **Atomic conditional `UPDATE ... WHERE balance >= amount`** as the sole mechanism — attractive
  because it needs no explicit `FOR UPDATE` syntax, but a transfer touches *two* rows (a debit and
  a credit), and those are still two separate `UPDATE` statements each taking their own row lock.
  Getting deadlock-avoidance right then means reasoning per-direction about which statement runs
  first — e.g. if the credit target happens to have the lower wallet id, doing an unconditional
  credit before confirming the debit succeeds is *not* obviously wrong, but it's the kind of thing
  that's easy to get backwards under a hurried implementation. Locking both rows up front, sorted,
  removes that reasoning entirely: by the time either row is written, both locks are already held in
  a globally consistent order, and the balance check is just an `if`.

**Deadlock avoidance:** both rows are locked in separate statements, always in ascending id order.
Two transfers referencing the same pair of wallets — even A→B and B→A fired at the same instant —
always request their locks in the same order, so the second transaction simply queues behind the
first. Neither can be holding one lock while waiting on the other, so there is no circular wait.

## Where idempotency lives

The uniqueness of `idempotency_key` is enforced by a DB unique constraint, and the claiming
`INSERT ... ON CONFLICT (idempotency_key) DO NOTHING` runs inside the *same transaction* as the
debit/credit — not before it, not in a separate check. The two wallet locks are acquired first,
so the transfer foreign-key checks cannot introduce a different lock order under contention. This
matters because Postgres's
`ON CONFLICT` blocks a conflicting insert until the other transaction holding that key finishes
(commits or rolls back); it does not report "duplicate" against a row that isn't durable yet. So a
concurrent duplicate never gets to race the money movement — it waits, then reads back the
already-committed outcome. Same-key/different-body is caught by comparing `request_hash` and
returns `409` without touching any balance.

## Consistency vs. availability

I chose consistency: a transfer that can't safely acquire its locks waits rather than serving a
provisional or possibly-stale balance, and the DB's `CHECK` constraints (`balance_paise >= 0`,
`amount_paise > 0`, `from <> to`) are a second, independent backstop against ever persisting an
invalid state, even if the application logic had a bug. What I gave up: throughput on a single hot
pair of wallets is bounded by how fast one transaction at a time can complete against that pair —
a system willing to tolerate eventual consistency (e.g. per-wallet actors reconciled later) could
push more concurrent throughput through a single wallet, at the cost of having to reason about
compensating transactions instead of simply preventing bad states from occurring. For a money
ledger, I'd rather be slower under contention than fast and wrong.

## Test-utility endpoint

`POST /wallets/{id}/seed` is **not** part of the four required endpoints. The exercise defines no
deposit/top-up API, but exercising transfers at all requires funded wallets, and burst scripts (or
anyone grading against a deployed URL with no DB access) need a portable way to do that. It mints
balance directly, bypassing the transfer ledger, and is intentionally excluded from the
conservation check, which is scoped to money moving via `POST /transfers`.

## Free-tier cost note

Target: ₹0. *(Fill in once deployed — e.g. "Render free web service + Render free Postgres" or
"Fly.io free allowance + Neon free Postgres tier".)*
