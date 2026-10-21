package com.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.payment.client.MockPaymentGatewayClient;
import com.platform.payment.dto.PaymentRequest;
import com.platform.payment.dto.PaymentResponse;
import com.platform.payment.dto.RefundRequest;
import com.platform.payment.dto.RefundResponse;
import com.platform.payment.entity.Payment;
import com.platform.payment.entity.PaymentStatus;
import com.platform.payment.entity.Refund;
import com.platform.payment.exception.PaymentDeclinedException;
import com.platform.payment.outbox.OutboxMessage;
import com.platform.payment.outbox.OutboxRepository;
import com.platform.payment.repository.PaymentRepository;
import com.platform.payment.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private MockPaymentGatewayClient gatewayClient;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImpl(
                paymentRepository,
                refundRepository,
                outboxRepository,
                gatewayClient,
                objectMapper
        );
    }

    @Test
    @DisplayName("processPayment: Successfully charges card and saves outbox event")
    void shouldProcessPaymentAndPersistOutbox() {
        PaymentRequest request = new PaymentRequest("ord-100", "cust-1", new BigDecimal("199.99"), "USD");
        String idempotencyKey = "idemp-pay-1";

        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(gatewayClient.processPayment(any(), any(), any(), any()))
                .thenReturn(MockPaymentGatewayClient.ProviderResult.success("mock-tx-12345"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.processPayment(request, idempotencyKey, "corr-100");

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(response.providerTransactionId()).isEqualTo("mock-tx-12345");

        // Verify outbox entry
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(captor.getValue().getAggregateId()).isEqualTo("ord-100");
    }

    @Test
    @DisplayName("processPayment: Idempotent replay without calling provider")
    void shouldReplayExistingPaymentWithoutCallingGateway() {
        PaymentRequest request = new PaymentRequest("ord-100", "cust-1", new BigDecimal("199.99"), "USD");
        String idempotencyKey = "idemp-pay-1";

        Payment existing = Payment.builder()
                .id("pay-existing")
                .orderId("ord-100")
                .customerId("cust-1")
                .amount(new BigDecimal("199.99"))
                .currency("USD")
                .status(PaymentStatus.COMPLETED)
                .idempotencyKey(idempotencyKey)
                .providerTransactionId("mock-tx-12345")
                .build();

        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.processPayment(request, idempotencyKey, "corr-101");

        assertThat(response.paymentId()).isEqualTo("pay-existing");
        verify(gatewayClient, never()).processPayment(any(), any(), any(), any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("processPayment: Handles decline, marks payment FAILED, persists outbox event and throws exception")
    void shouldHandleCardDeclineAndPersistFailedOutbox() {
        PaymentRequest request = new PaymentRequest("ord-101", "cust-decline", new BigDecimal("500.00"), "USD");
        String idempotencyKey = "idemp-pay-2";

        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(gatewayClient.processPayment(any(), any(), any(), any()))
                .thenReturn(MockPaymentGatewayClient.ProviderResult.failure("CARD_DECLINED", "Insufficient funds"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> paymentService.processPayment(request, idempotencyKey, "corr-102"))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("Insufficient funds");

        // Verify outbox entry for PAYMENT_FAILED
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    @DisplayName("refundPayment: Successfully creates refund and marks payment REFUNDED")
    void shouldRefundPayment() {
        Payment payment = Payment.builder()
                .id("pay-100")
                .orderId("ord-100")
                .customerId("cust-1")
                .amount(new BigDecimal("199.99"))
                .currency("USD")
                .status(PaymentStatus.COMPLETED)
                .idempotencyKey("idemp-1")
                .build();

        when(paymentRepository.findById("pay-100")).thenReturn(Optional.of(payment));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        RefundRequest request = new RefundRequest("ord-100", new BigDecimal("199.99"), "Order cancelled");
        RefundResponse response = paymentService.refundPayment("pay-100", request, "corr-103");

        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("199.99"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentRepository, times(1)).save(payment);
    }
}
