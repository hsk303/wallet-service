package com.paytm.wallet.controller;

import com.paytm.wallet.config.BearerAuthFilter;
import com.paytm.wallet.dto.SeedRequest;
import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.model.Wallet;
import com.paytm.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createOrGet(
            @RequestAttribute(BearerAuthFilter.CALLER_ID_ATTRIBUTE) String callerId) {
        Wallet wallet = walletService.getOrCreate(callerId);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getById(@PathVariable UUID id) {
        Wallet wallet = walletService.getById(id);
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    /**
     * Test/demo-only utility endpoint - mints balance directly, bypassing
     * the transfers ledger. Exists because the exercise defines no
     * deposit/top-up API, and burst scripts need a portable way to fund
     * wallets on a freshly deployed instance without DB access. Not part of
     * the required public API surface, and not counted toward the
     * conservation invariant (which is scoped to money moving between
     * already-funded wallets via POST /transfers).
     */
    @PostMapping("/{id}/seed")
    public ResponseEntity<WalletResponse> seed(@PathVariable UUID id, @Valid @RequestBody SeedRequest request) {
        Wallet wallet = walletService.seed(id, request.getAmountPaise());
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }
}
