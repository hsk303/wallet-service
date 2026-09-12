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
_is_ the caller's user id — there's no separate registration step; auth sophistication is
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

# Reverse/refund a completed transfer (own idempotency key)
curl -X POST localhost:8080/transfers/<id>/reverse \
  -H "Authorization: Bearer alice" -H "Content-Type: application/json" \
  -d '{"idempotency_key":"reverse-key-1"}'
```

## Burst scripts

```bash
BASE_URL=http://localhost:8080 ./scripts/burst-get-or-create.sh 50
BASE_URL=http://localhost:8080 ./scripts/burst-idempotent-retry.sh 30
BASE_URL=http://localhost:8080 ./scripts/burst-conservation.sh 300
BASE_URL=http://localhost:8080 ./scripts/burst-reversal.sh 2
```

Requires `curl` and `jq`. Point `BASE_URL` at a deployed instance to reproduce the same checks
remotely.

## Render deployment

For a hosted deployment, set these Render environment variables:

```text
PORT=8080
JDBC_DATABASE_URL=jdbc:postgresql://<neon-host>/<database>?sslmode=require
DB_USER=<neon-user>
DB_PASSWORD=<neon-password>
```

The `JDBC_DATABASE_URL` value takes precedence over the local `DB_HOST`/`DB_PORT` defaults.
Do not set it to `localhost`; that refers to the Render web-service container, not Neon.

## Design write-up

See `WRITEUP.md` for the data model, the locking/idempotency reasoning, and the
consistency-vs-availability call.

## Operations runbook

See [RUNBOOK.md](RUNBOOK.md) for local startup, live Render deployment, API usage,
grading bursts, observability, and troubleshooting.
