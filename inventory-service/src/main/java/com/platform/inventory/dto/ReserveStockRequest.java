package com.platform.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReserveStockRequest(
        @NotBlank(message = "Order ID must not be blank")
        String orderId,

        @NotEmpty(message = "Items list must not be empty")
        @Valid
        List<ItemReservationRequest> items
) {}
