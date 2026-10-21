package com.platform.common.event.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentCompletedEvent(
        String paymentId,
        String orderId,
        String customerId,
        BigDecimal amount,
        String currency,
        String providerTransactionId,
        String providerName,
        Instant processedAt
) {}
