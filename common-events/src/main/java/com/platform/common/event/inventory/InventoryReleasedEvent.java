package com.platform.common.event.inventory;

import java.util.List;

public record InventoryReleasedEvent(
        String orderId,
        List<ReleasedItem> releasedItems
) {
    public record ReleasedItem(
            String sku,
            int quantity
    ) {}
}
