package com.platform.inventory.dto;

import com.platform.inventory.entity.ReservationStatus;

import java.time.Instant;
import java.util.List;

public record ReserveStockResponse(
        String orderId,
        ReservationStatus status,
        List<ItemReservationRequest> reservedItems,
        Instant reservedAt
) {}
