package com.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.event.payment.PaymentCompletedEvent;
import com.platform.common.event.payment.PaymentFailedEvent;
import com.platform.payment.client.MockPaymentGatewayClient;
import com.platform.payment.dto.PaymentRequest;
import com.platform.payment.dto.PaymentResponse;
import com.platform.payment.dto.RefundRequest;
import com.platform.payment.dto.RefundResponse;
import com.platform.payment.entity.Payment;
import com.platform.payment.entity.PaymentStatus;
import com.platform.payment.entity.Refund;
import com.platform.payment.exception.PaymentDeclinedException;
import com.platform.payment.exception.PaymentNotFoundException;
import com.platform.payment.outbox.OutboxMessage;
import com.platform.payment.outbox.OutboxRepository;
import com.platform.payment.repository.PaymentRepository;
import com.platform.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OutboxRepository outboxRepository;
    private final MockPaymentGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;

    public PaymentServiceImpl(
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            OutboxRepository outboxRepository,
            MockPaymentGatewayClient gatewayClient,
            ObjectMapper objectMapper
    ) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.outboxRepository = outboxRepository;
        this.gatewayClient = gatewayClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PaymentResponse processPayment(PaymentRequest request, String idempotencyKey, String correlationId) {
        log.info("Processing payment for order: {} with idempotencyKey: {}", request.orderId(), idempotencyKey);

        // 1. Idempotency Check
        Optional<Payment> existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existingPayment.isPresent()) {
            log.info("Idempotent payment replay detected for order: {}", request.orderId());
            return mapToResponse(existingPayment.get());
        }

        // 2. Call Mock Payment Provider (Protected by Resilience4j Circuit Breaker)
        MockPaymentGatewayClient.ProviderResult result = gatewayClient.processPayment(
                request.orderId(),
                request.customerId(),
                request.amount(),
                request.currency()
        );

        String paymentId = "pay-" + UUID.randomUUID().toString().substring(0, 8);
        PaymentStatus status = result.success() ? PaymentStatus.COMPLETED : PaymentStatus.FAILED;

        // 3. Persist Payment Record
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(request.orderId())
                .customerId(request.customerId())
                .amount(request.amount())
                .currency(request.currency())
                .status(status)
                .idempotencyKey(idempotencyKey)
                .providerTransactionId(result.transactionId())
                .failureReason(result.errorMessage())
                .build();

        Payment savedPayment = paymentRepository.save(payment);

        // 4. Record Outbox Event in the same ACID transaction
        try {
            if (result.success()) {
                PaymentCompletedEvent event = new PaymentCompletedEvent(
                        savedPayment.getId(),
                        savedPayment.getOrderId(),
                        savedPayment.getCustomerId(),
                        savedPayment.getAmount(),
                        savedPayment.getCurrency(),
                        savedPayment.getProviderTransactionId(),
                        "MOCK_PROVIDER",
                        Instant.now()
                );
                OutboxMessage outbox = OutboxMessage.builder()
                        .aggregateType("PAYMENT")
                        .aggregateId(savedPayment.getOrderId())
                        .eventType("PAYMENT_COMPLETED")
                        .payload(objectMapper.writeValueAsString(event))
                        .correlationId(correlationId)
                        .status("PENDING")
                        .build();
                outboxRepository.save(outbox);
            } else {
                PaymentFailedEvent event = new PaymentFailedEvent(
                        savedPayment.getId(),
                        savedPayment.getOrderId(),
                        savedPayment.getCustomerId(),
                        savedPayment.getAmount(),
                        savedPayment.getCurrency(),
                        result.errorCode(),
                        result.errorMessage()
                );
                OutboxMessage outbox = OutboxMessage.builder()
                        .aggregateType("PAYMENT")
                        .aggregateId(savedPayment.getOrderId())
                        .eventType("PAYMENT_FAILED")
                        .payload(objectMapper.writeValueAsString(event))
                        .correlationId(correlationId)
                        .status("PENDING")
                        .build();
                outboxRepository.save(outbox);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payment outbox event for order: {}", request.orderId(), e);
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }

        if (!result.success()) {
            throw new PaymentDeclinedException(result.errorCode(), result.errorMessage());
        }

        return mapToResponse(savedPayment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(String paymentId) {
        return paymentRepository.findById(paymentId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new PaymentNotFoundException("orderId: " + orderId));
    }

    @Override
    @Transactional
    public RefundResponse refundPayment(String paymentId, RefundRequest request, String correlationId) {
        log.info("Processing refund for payment: {}, order: {}", paymentId, request.orderId());

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        Refund refund = Refund.builder()
                .payment(payment)
                .orderId(request.orderId())
                .amount(request.amount())
                .status("COMPLETED")
                .reason(request.reason() != null ? request.reason() : "Customer compensation refund")
                .build();

        Refund savedRefund = refundRepository.save(refund);

        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        return new RefundResponse(
                savedRefund.getId(),
                payment.getId(),
                savedRefund.getOrderId(),
                savedRefund.getAmount(),
                savedRefund.getStatus(),
                savedRefund.getReason(),
                savedRefund.getCreatedAt()
        );
    }

    private PaymentResponse mapToResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getProviderTransactionId(),
                payment.getFailureReason(),
                payment.getIdempotencyKey(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
