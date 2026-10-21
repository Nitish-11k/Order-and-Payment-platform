package com.platform.common.event.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderConfirmedEvent(
        String orderId,
        String customerId,
        BigDecimal totalAmount,
        String currency,
        String paymentTransactionId,
        Instant confirmedAt
) {}
