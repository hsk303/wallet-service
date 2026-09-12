package com.paytm.wallet.service;

import com.paytm.wallet.model.Transfer;

import java.util.UUID;

public class TransferResult {
    private final UUID id;
    private final UUID from;
    private final UUID to;
    private final long amountPaise;
    private final String status;

    private TransferResult(UUID id, UUID from, UUID to, long amountPaise, String status) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.amountPaise = amountPaise;
        this.status = status;
    }

    public static TransferResult completed(UUID id, UUID from, UUID to, long amountPaise) {
        return new TransferResult(id, from, to, amountPaise, "COMPLETED");
    }

    public static TransferResult declined(UUID id, UUID from, UUID to, long amountPaise) {
        return new TransferResult(id, from, to, amountPaise, "DECLINED_INSUFFICIENT_FUNDS");
    }

    public static TransferResult from(Transfer t) {
        return new TransferResult(t.getId(), t.getFromWalletId(), t.getToWalletId(), t.getAmountPaise(), t.getStatus());
    }

    public UUID getId() {
        return id;
    }

    public UUID getFrom() {
        return from;
    }

    public UUID getTo() {
        return to;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getStatus() {
        return status;
    }
}
