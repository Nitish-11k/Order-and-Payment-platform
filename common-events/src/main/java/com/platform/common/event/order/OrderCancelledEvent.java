package com.platform.common.event.order;

import java.time.Instant;

public record OrderCancelledEvent(
        String orderId,
        String customerId,
        String reason,
        String details,
        Instant cancelledAt
) {}
