package com.platform.inventory.dto;

public record InventoryResponse(
        String sku,
        String productName,
        int availableQuantity,
        int reservedQuantity,
        Long version
) {}
