-- Wallets: one row per user, balance is always integer paise.
CREATE TABLE wallets (
    id             UUID PRIMARY KEY,
    user_id        VARCHAR(255) NOT NULL,
    balance_paise  BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallets_user_id UNIQUE (user_id),
    CONSTRAINT chk_wallets_balance_nonneg CHECK (balance_paise >= 0)
);

-- Transfers: one row per attempted transfer, keyed by client idempotency_key.
-- request_hash lets us tell "same key, same body" (safe retry) apart from
-- "same key, different body" (client error -> 409) without re-parsing the
-- original request.
CREATE TABLE transfers (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL,
    from_wallet_id   UUID NOT NULL REFERENCES wallets(id),
    to_wallet_id     UUID NOT NULL REFERENCES wallets(id),
    amount_paise     BIGINT NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    status           VARCHAR(32) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT chk_transfers_from_ne_to CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX idx_transfers_from_wallet ON transfers (from_wallet_id);
CREATE INDEX idx_transfers_to_wallet ON transfers (to_wallet_id);
