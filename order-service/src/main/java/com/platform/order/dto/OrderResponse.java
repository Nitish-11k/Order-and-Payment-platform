package com.platform.order.dto;

import com.platform.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String orderId,
        String customerId,
        BigDecimal totalAmount,
        String currency,
        OrderStatus status,
        String idempotencyKey,
        String failureReason,
        Long version,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {}
