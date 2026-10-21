package com.platform.common.event.order;

import java.math.BigDecimal;
import java.util.List;

public record OrderCreatedEvent(
        String orderId,
        String customerId,
        BigDecimal totalAmount,
        String currency,
        List<OrderItemPayload> items,
        ShippingAddressPayload shippingAddress
) {
    public record OrderItemPayload(
            String sku,
            int quantity,
            BigDecimal unitPrice
    ) {}

    public record ShippingAddressPayload(
            String street,
            String city,
            String state,
            String postalCode,
            String country
    ) {}
}
