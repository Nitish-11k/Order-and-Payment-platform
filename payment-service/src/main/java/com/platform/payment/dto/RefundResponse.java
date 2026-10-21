package com.platform.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID refundId,
        String paymentId,
        String orderId,
        BigDecimal amount,
        String status,
        String reason,
        Instant createdAt
) {}
