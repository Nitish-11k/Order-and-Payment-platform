package com.platform.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ItemReservationRequest(
        @NotBlank(message = "SKU must not be blank")
        String sku,

        @Min(value = 1, message = "Quantity must be at least 1")
        int quantity
) {}
