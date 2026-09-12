package com.paytm.wallet.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A wallet's balance is always an integer number of paise. Never a float,
 * never a decimal-rupees representation.
 */
public class Wallet {
    private final UUID id;
    private final String userId;
    private final long balancePaise;
    private final Instant createdAt;

    public Wallet(UUID id, String userId, long balancePaise, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.balancePaise = balancePaise;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
