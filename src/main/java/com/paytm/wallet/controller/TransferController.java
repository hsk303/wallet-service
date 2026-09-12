package com.paytm.wallet.controller;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.service.TransferResult;
import com.paytm.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody TransferRequest request) {
        TransferResult result = transferService.transfer(
                request.getFrom(), request.getTo(), request.getAmountPaise(), request.getIdempotencyKey());
        return ResponseEntity.ok(TransferResponse.from(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getById(@PathVariable UUID id) {
        TransferResult result = transferService.getById(id);
        return ResponseEntity.ok(TransferResponse.from(result));
    }
}
