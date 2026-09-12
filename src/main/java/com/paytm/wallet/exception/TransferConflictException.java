package com.paytm.wallet.exception;

public class TransferConflictException extends RuntimeException {
    public TransferConflictException(String message) {
        super(message);
    }
}