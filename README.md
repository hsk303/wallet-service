# wallet-service

A small wallet & P2P transfer service. Money is always integer paise.

## Run locally

```bash
docker compose up --build
```

This brings up Postgres and the app together. The app runs its schema migration
(Flyway, `src/main/resources/db/migration/V1__init.sql`) automatically on startup.

Health check: `curl http://localhost:8080/actuator/health`
Metrics: `curl http://localhost:8080/actuator/prometheus`
Interactive API documentation: `http://localhost:8080/swagger-ui/index.html`

In Swagger UI, click **Authorize** and enter `alice` as the bearer token value.

## API

Auth: every request except `/actuator/*` needs `Authorization: Bearer <token>`. The token value
*is* the caller's user id — there's no separate registration step; auth sophistication is
explicitly out of scope for this exercise.

```bash
# Get-or-create a wallet for the caller
curl -X POST localhost:8080/wallets -H "Authorization: Bearer alice"
# -> {"id":"...","user_id":"alice","balance_paise":0}

# Check balance
curl localhost:8080/wallets/<id>

# Test-only: fund a wallet (not part of the required API — see WRITEUP.md)
curl -X POST localhost:8080/wallets/<id>/seed \
  -H "Authorization: Bearer alice" -H "Content-Type: application/json" \
  -d '{"amount_paise": 100000}'

# Transfer
curl -X POST localhost:8080/transfers \
  -H "Authorization: Bearer alice" -H "Content-Type: application/json" \
  -d '{"from":"<walletA>","to":"<walletB>","amount_paise":1000,"idempotency_key":"key-1"}'

# Check a transfer
curl localhost:8080/transfers/<id>
```

## Burst scripts

```bash
BASE_URL=http://localhost:8080 ./scripts/burst-get-or-create.sh 50
BASE_URL=http://localhost:8080 ./scripts/burst-idempotent-retry.sh 30
BASE_URL=http://localhost:8080 ./scripts/burst-conservation.sh 300
```

Requires `curl` and `jq`. Point `BASE_URL` at a deployed instance to reproduce the same checks
remotely.

## Design write-up

See `WRITEUP.md` for the data model, the locking/idempotency reasoning, and the
consistency-vs-availability call.

## Note on this build

I could not run `mvn`/`docker compose up` myself while assembling this — my sandbox's network
allowlist doesn't include Maven Central, so dependency resolution isn't possible here. Please run
`docker compose up --build` and the burst scripts locally before you rely on this for grading or a
live demo, and skim the actual Java for anything that doesn't look right rather than assuming it's
been compiler-checked.
