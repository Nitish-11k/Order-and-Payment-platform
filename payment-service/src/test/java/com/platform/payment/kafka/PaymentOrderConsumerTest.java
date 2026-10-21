package com.platform.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.payment.dto.PaymentRequest;
import com.platform.payment.dto.PaymentResponse;
import com.platform.payment.entity.PaymentStatus;
import com.platform.payment.entity.ProcessedEvent;
import com.platform.payment.exception.PaymentDeclinedException;
import com.platform.payment.repository.ProcessedEventRepository;
import com.platform.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentOrderConsumerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private PaymentOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentOrderConsumer(paymentService, processedEventRepository, objectMapper);
    }

    @Test
    @DisplayName("consumePaymentTrigger: Happy path processes payment, records processed_events and acknowledges")
    void shouldProcessPaymentAndAcknowledge() {
        String jsonPayload = """
                {
                    "eventType": "PAYMENT_TRIGGERED",
                    "eventId": "evt-trigger-1",
                    "payload": {
                        "orderId": "ord-200",
                        "customerId": "cust-2",
                        "amount": 250.00,
                        "currency": "USD"
                    }
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.v1.events", 0, 200L, "ord-200", jsonPayload);

        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup("evt-trigger-1", "payment-service-group"))
                .thenReturn(false);
        when(paymentService.processPayment(any(PaymentRequest.class), any(), eq("evt-trigger-1")))
                .thenReturn(new PaymentResponse("pay-200", "ord-200", "cust-2", new BigDecimal("250.00"), "USD",
                        PaymentStatus.COMPLETED, "mock-tx-200", null, "idemp-saga-pay-ord-200", Instant.now(), Instant.now()));

        consumer.consumePaymentTrigger(record, acknowledgment);

        verify(paymentService).processPayment(any(PaymentRequest.class), eq("idemp-saga-pay-ord-200"), eq("evt-trigger-1"));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consumePaymentTrigger: Skips duplicate payment trigger (Idempotent Consumer)")
    void shouldSkipDuplicatePaymentTrigger() {
        String jsonPayload = """
                {
                    "eventType": "PAYMENT_TRIGGERED",
                    "eventId": "evt-trigger-duplicate",
                    "payload": {
                        "orderId": "ord-200",
                        "customerId": "cust-2",
                        "amount": 250.00
                    }
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.v1.events", 0, 201L, "ord-200", jsonPayload);

        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup("evt-trigger-duplicate", "payment-service-group"))
                .thenReturn(true);

        consumer.consumePaymentTrigger(record, acknowledgment);

        verify(paymentService, never()).processPayment(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consumePaymentTrigger: Handles PaymentDeclinedException gracefully without throwing unhandled error")
    void shouldHandlePaymentDeclinedGracefully() {
        String jsonPayload = """
                {
                    "eventType": "PAYMENT_TRIGGERED",
                    "eventId": "evt-trigger-decline",
                    "payload": {
                        "orderId": "ord-201",
                        "customerId": "cust-decline",
                        "amount": 500.00
                    }
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.v1.events", 0, 202L, "ord-201", jsonPayload);

        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup("evt-trigger-decline", "payment-service-group"))
                .thenReturn(false);
        doThrow(new PaymentDeclinedException("CARD_DECLINED", "Card has insufficient balance"))
                .when(paymentService).processPayment(any(PaymentRequest.class), any(), eq("evt-trigger-decline"));

        consumer.consumePaymentTrigger(record, acknowledgment);

        // Even on decline, event is marked processed and acknowledged because failure was handled & outbox emitted
        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(acknowledgment).acknowledge();
    }
}
