package com.platform.payment.exception;

public class PaymentDeclinedException extends RuntimeException {

    private final String errorCode;

    public PaymentDeclinedException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
