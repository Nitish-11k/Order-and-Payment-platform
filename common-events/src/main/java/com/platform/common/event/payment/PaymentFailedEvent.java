package com.platform.common.event.payment;

import java.math.BigDecimal;

public record PaymentFailedEvent(
        String paymentId,
        String orderId,
        String customerId,
        BigDecimal amount,
        String currency,
        String errorCode,
        String errorMessage
) {}
