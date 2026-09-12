package com.paytm.wallet.exception;

/** Same idempotency_key reused with a materially different request body. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
