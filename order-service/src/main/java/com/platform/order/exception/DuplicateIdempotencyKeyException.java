package com.platform.order.exception;

public class DuplicateIdempotencyKeyException extends RuntimeException {
    public DuplicateIdempotencyKeyException(String key) {
        super("An order with idempotency key '" + key + "' has already been processed with different parameters.");
    }
}
