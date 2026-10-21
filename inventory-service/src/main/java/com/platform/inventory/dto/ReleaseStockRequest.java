package com.platform.inventory.dto;

import jakarta.validation.constraints.NotBlank;

public record ReleaseStockRequest(
        @NotBlank(message = "Order ID must not be blank")
        String orderId
) {}
