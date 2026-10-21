package com.platform.common.event.order;

import java.math.BigDecimal;

public record PaymentTriggeredEvent(
        String orderId,
        String customerId,
        BigDecimal amount,
        String currency
) {}
