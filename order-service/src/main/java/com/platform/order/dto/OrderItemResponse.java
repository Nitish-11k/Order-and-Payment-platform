package com.platform.order.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        String sku,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal
) {}
