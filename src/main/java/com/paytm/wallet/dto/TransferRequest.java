package com.paytm.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * JSON field names are snake_case on the wire (from, to, amount_paise,
 * idempotency_key) via the global Jackson SNAKE_CASE naming strategy - see
 * application.yml.
 */
public class TransferRequest {

    @NotNull
    private UUID from;

    @NotNull
    private UUID to;

    @NotNull
    @Positive
    private Long amountPaise;

    @NotBlank
    private String idempotencyKey;

    public UUID getFrom() {
        return from;
    }

    public void setFrom(UUID from) {
        this.from = from;
    }

    public UUID getTo() {
        return to;
    }

    public void setTo(UUID to) {
        this.to = to;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public void setAmountPaise(Long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
