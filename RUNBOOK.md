# Wallet Service Runbook

This document covers the supported local and live workflows for the wallet service.

## 1. Prerequisites

Install:

- Docker Desktop
- `curl`
- `jq`
- Git, if deploying from a repository

Never commit database passwords, Neon connection strings, or Render environment files.
If a password is exposed, rotate it in Neon immediately.

## 2. Run locally

From the repository root:

```bash
docker compose up -d --build
```

Check the containers and application health:

```bash
docker compose ps
curl -sS http://localhost:8080/actuator/health
```

Expected health response:

```json
{ "status": "UP" }
```

Useful local URLs:

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Metrics: http://localhost:8080/actuator/prometheus

Stop the local stack:

```bash
docker compose down
```

The default local database is PostgreSQL at `db:5432`, with database `wallet`, user
`wallet`, and password `wallet`. These values are only for local Compose use.

## 3. Authentication

There is no login endpoint and no JWT service. This exercise uses a simple bearer identity:

```text
Authorization: Bearer alice
```

The token value is treated as the caller's user ID. In Swagger UI, click **Authorize** and
enter `alice`.

## 4. Basic API flow

Create or retrieve a wallet:

```bash
curl -sS -X POST http://localhost:8080/wallets \
  -H 'Authorization: Bearer alice'
```

Read a wallet. Wallet reads also require authorization:

```bash
curl -sS http://localhost:8080/wallets/<wallet-id> \
  -H 'Authorization: Bearer alice'
```

Fund a wallet for testing only:

```bash
curl -sS -X POST http://localhost:8080/wallets/<wallet-id>/seed \
  -H 'Authorization: Bearer alice' \
  -H 'Content-Type: application/json' \
  -d '{"amount_paise":100000}'
```

Transfer money:

```bash
curl -sS -X POST http://localhost:8080/transfers \
  -H 'Authorization: Bearer alice' \
  -H 'Content-Type: application/json' \
  -d '{"from":"<wallet-a>","to":"<wallet-b>","amount_paise":1000,"idempotency_key":"key-1"}'
```

Check a transfer:

```bash
curl -sS http://localhost:8080/transfers/<transfer-id> \
  -H 'Authorization: Bearer alice'
```

Reverse a completed transfer. The recipient is debited and the original sender is credited;
the reversal has its own idempotency key:

```bash
curl -sS -X POST http://localhost:8080/transfers/<transfer-id>/reverse \
  -H 'Authorization: Bearer alice' \
  -H 'Content-Type: application/json' \
  -d '{"idempotency_key":"reverse-key-1"}'
```

All amounts are integer paise. The seed endpoint is a test utility and is not a production
deposit API.

## 5. Live Render deployment

Current live base URL:

```text
https://wallet-service-1-y041.onrender.com
```

Set these variables on the Render web service:

```text
JDBC_DATABASE_URL=jdbc:postgresql://<neon-host>/<database>?sslmode=require&channel_binding=require
DB_USER=<neon-user>
DB_PASSWORD=<rotated-neon-password>
```

Render supplies `PORT` automatically. Do not use `localhost` in `JDBC_DATABASE_URL`.
The application supports Render's assigned port through `PORT`.

The application has Flyway baseline settings for a pre-existing Neon `public` schema:

```yaml
baseline-on-migrate: true
baseline-version: 0
```

This allows Flyway to create its history table and apply `V1__init.sql` when the database
already contains an empty or unrelated schema. Do not delete or modify production tables
without a backup and an explicit migration plan.

After changing Render variables or code:

1. Click **Save Changes** in Render.
2. Trigger **Manual Deploy -> Deploy latest commit**.
3. Wait for the health check to pass.
4. Check the deployment logs for `Started WalletServiceApplication` and a successful Flyway migration.

Live health check:

```bash
export BASE_URL='https://wallet-service-1-y041.onrender.com'
curl -sS "$BASE_URL/actuator/health"
```

## 6. Run the grading bursts

Run these from the repository root, sequentially, against the live service:

```bash
export BASE_URL='https://wallet-service-1-y041.onrender.com'

BASE_URL="$BASE_URL" ./scripts/burst-get-or-create.sh 50
BASE_URL="$BASE_URL" ./scripts/burst-idempotent-retry.sh 30
BASE_URL="$BASE_URL" ./scripts/burst-conservation.sh 300
BASE_URL="$BASE_URL" ./scripts/burst-reversal.sh 2
```

Expected results:

- Get-or-create: one distinct wallet ID and all requests successful.
- Idempotency: one distinct transfer ID; source decreases once and target increases once.
- Conservation: total balance before and after is equal; no wallet is negative; no `500` responses.

These tests create persistent test data in Neon. Use them only against a disposable or
appropriately managed grading database.

## 7. Observability

Render logs are available in the Render service dashboard under **Logs**. Application logs
are structured JSON and include request correlation IDs.

Live metrics:

```bash
curl -sS "$BASE_URL/actuator/prometheus" \
  | grep -E 'wallet_transfers_|http_server_requests_seconds'
```

Important domain counters include:

- `wallet_transfers_completed_total`
- `wallet_transfers_declined_insufficient_funds_total`
- `wallet_transfers_idempotent_replay_total`
- `wallet_wallets_created_total`

Swagger UI is available at:

```text
https://wallet-service-1-y041.onrender.com/swagger-ui/index.html
```

## 8. Troubleshooting

### `Connection to localhost:5432 refused`

`JDBC_DATABASE_URL` is missing or still points to localhost. Set the hosted Neon JDBC URL
on the Render web service and redeploy.

### `password authentication failed`

Reset the Neon password, update `DB_PASSWORD` in Render, remove accidental whitespace, save,
and redeploy. Never paste the password into Git or this document.

### Flyway reports a non-empty schema with no history table

Deploy the version containing the Flyway baseline settings shown above. The service must
run the updated image before retrying deployment.

### Render reports no open port

The application takes time to initialize Spring and Flyway. Confirm that the database
connection succeeds first. Render should detect the port after the application finishes
startup; do not hardcode Render's assigned port.

### API returns `401`

Add the header:

```text
Authorization: Bearer <user-id>
```

### API returns `409` for a transfer

The idempotency key was already used with a different transfer body. Use a new key or resend
the original body unchanged.

## 9. Stop or reset local data

Stop containers but keep the local database volume:

```bash
docker compose down
```

Remove local database data as well:

```bash
docker compose down -v
```

The second command is destructive for local data only.
