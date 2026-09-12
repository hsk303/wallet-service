package com.paytm.wallet.dto;

import com.paytm.wallet.service.TransferResult;

import java.util.UUID;

public class TransferResponse {
    private UUID id;
    private UUID from;
    private UUID to;
    private long amountPaise;
    private String status;

    public static TransferResponse from(TransferResult result) {
        TransferResponse r = new TransferResponse();
        r.id = result.getId();
        r.from = result.getFrom();
        r.to = result.getTo();
        r.amountPaise = result.getAmountPaise();
        r.status = result.getStatus();
        return r;
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
