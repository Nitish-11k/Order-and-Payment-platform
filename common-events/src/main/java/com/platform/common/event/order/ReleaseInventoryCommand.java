package com.platform.common.event.order;

public record ReleaseInventoryCommand(
        String orderId,
        String reason
) {}
