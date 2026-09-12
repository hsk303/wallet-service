package com.paytm.wallet.model;

import java.time.Instant;
import java.util.UUID;

public class Transfer {
    private final UUID id;
    private final String idempotencyKey;
    private final UUID fromWalletId;
    private final UUID toWalletId;
    private final long amountPaise;
    private final String requestHash;
    private final String status;
    private final Instant createdAt;

    public Transfer(UUID id, String idempotencyKey, UUID fromWalletId, UUID toWalletId,
                     long amountPaise, String requestHash, String status, Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amountPaise = amountPaise;
        this.requestHash = requestHash;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getFromWalletId() {
        return fromWalletId;
    }

    public UUID getToWalletId() {
        return toWalletId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
