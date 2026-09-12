package com.paytm.wallet.dto;

import jakarta.validation.constraints.NotBlank;

public class ReverseRequest {
    @NotBlank
    private String idempotencyKey;

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}