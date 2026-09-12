package com.paytm.wallet.dto;

import com.paytm.wallet.model.Wallet;

import java.util.UUID;

public class WalletResponse {
    private UUID id;
    private String userId;
    private long balancePaise;

    public static WalletResponse from(Wallet wallet) {
        WalletResponse r = new WalletResponse();
        r.id = wallet.getId();
        r.userId = wallet.getUserId();
        r.balancePaise = wallet.getBalancePaise();
        return r;
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
}
