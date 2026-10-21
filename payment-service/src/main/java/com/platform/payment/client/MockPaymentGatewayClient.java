package com.platform.payment.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class MockPaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGatewayClient.class);

    public record ProviderResult(
            boolean success,
            String transactionId,
            String errorCode,
            String errorMessage
    ) {
        public static ProviderResult success(String transactionId) {
            return new ProviderResult(true, transactionId, null, null);
        }

        public static ProviderResult failure(String errorCode, String errorMessage) {
            return new ProviderResult(false, null, errorCode, errorMessage);
        }
    }

    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "fallbackProcessPayment")
    public ProviderResult processPayment(String orderId, String customerId, BigDecimal amount, String currency) {
        log.info("Contacting external Mock Payment Gateway for order: {}, amount: {} {}", orderId, amount, currency);

        // Simulation 1: High latency / network outage trigger for Circuit Breaker
        if (customerId != null && customerId.startsWith("cust-timeout")) {
            log.warn("Simulated network timeout triggered for customer: {}", customerId);
            throw new RuntimeException("Simulated external payment gateway connection timeout");
        }

        // Simulation 2: Card decline trigger
        if (customerId != null && customerId.startsWith("cust-decline") || amount.compareTo(new BigDecimal("10000.00")) > 0) {
            log.warn("Mock card processor declined transaction for order: {}", orderId);
            return ProviderResult.failure("CARD_DECLINED", "Card balance insufficient or fraud rule triggered.");
        }

        // Success path
        String transactionId = "mock-tx-" + UUID.randomUUID().toString().substring(0, 12);
        log.info("Mock Payment Gateway processed transaction successfully: {}", transactionId);
        return ProviderResult.success(transactionId);
    }

    @SuppressWarnings("unused")
    public ProviderResult fallbackProcessPayment(String orderId, String customerId, BigDecimal amount, String currency, Throwable t) {
        log.error("Circuit breaker OPEN or fallback triggered for order {}: {}", orderId, t.getMessage());
        return ProviderResult.failure("PROVIDER_UNAVAILABLE", "Payment gateway is currently experiencing outages: " + t.getMessage());
    }
}
