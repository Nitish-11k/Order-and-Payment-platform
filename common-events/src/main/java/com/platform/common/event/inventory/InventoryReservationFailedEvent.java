package com.platform.common.event.inventory;

public record InventoryReservationFailedEvent(
        String orderId,
        String failedSku,
        int requestedQuantity,
        int availableQuantity,
        String failureReason
) {}
