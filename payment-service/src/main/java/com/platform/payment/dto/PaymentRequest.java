package com.platform.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotBlank(message = "Order ID must not be blank")
        String orderId,

        @NotBlank(message = "Customer ID must not be blank")
        String customerId,

        @NotNull(message = "Amount must not be null")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        String currency
) {
    public PaymentRequest {
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
    }
}
