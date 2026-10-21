package com.platform.payment.dto;

import com.platform.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String orderId,
        String customerId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String providerTransactionId,
        String failureReason,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt
) {}
