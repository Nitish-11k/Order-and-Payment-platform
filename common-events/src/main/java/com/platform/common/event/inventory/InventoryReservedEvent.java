package com.platform.common.event.inventory;

import java.time.Instant;
import java.util.List;

public record InventoryReservedEvent(
        String orderId,
        List<ReservedItem> reservations,
        Instant reservedUntil
) {
    public record ReservedItem(
            String sku,
            int reservedQuantity
    ) {}
}
