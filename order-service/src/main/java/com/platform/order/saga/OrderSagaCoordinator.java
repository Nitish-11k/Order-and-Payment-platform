package com.platform.order.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.event.order.OrderCancelledEvent;
import com.platform.common.event.order.OrderConfirmedEvent;
import com.platform.common.event.order.PaymentTriggeredEvent;
import com.platform.common.event.order.ReleaseInventoryCommand;
import com.platform.order.entity.Order;
import com.platform.order.entity.OrderStatus;
import com.platform.order.exception.OrderNotFoundException;
import com.platform.order.outbox.OutboxMessage;
import com.platform.order.outbox.OutboxRepository;
import com.platform.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class OrderSagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaCoordinator.class);

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderSagaCoordinator(
            OrderRepository orderRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void onInventoryReserved(String orderId, String correlationId) {
        log.info("Saga Step: Inventory reserved for order {}. Triggering payment.", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.setStatus(OrderStatus.INVENTORY_RESERVED);
        orderRepository.save(order);

        // Enqueue payment trigger command/event to Kafka via outbox
        PaymentTriggeredEvent event = new PaymentTriggeredEvent(
                order.getId(),
                order.getCustomerId(),
                order.getTotalAmount(),
                order.getCurrency()
        );

        enqueueOutbox(order.getId(), "PAYMENT_TRIGGERED", event, correlationId);
    }

    @Transactional
    public void onInventoryReservationFailed(String orderId, String reason, String correlationId) {
        log.warn("Saga Step: Inventory reservation failed for order {}. Reason: {}. Cancelling order.", orderId, reason);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.setStatus(OrderStatus.CANCELLED);
        order.setFailureReason("INSUFFICIENT_STOCK: " + reason);
        orderRepository.save(order);

        OrderCancelledEvent event = new OrderCancelledEvent(
                order.getId(),
                order.getCustomerId(),
                "INSUFFICIENT_STOCK",
                reason,
                Instant.now()
        );

        enqueueOutbox(order.getId(), "ORDER_CANCELLED", event, correlationId);
    }

    @Transactional
    public void onPaymentCompleted(String orderId, String transactionId, String correlationId) {
        log.info("Saga Step: Payment completed for order {}. Confirming order.", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        OrderConfirmedEvent event = new OrderConfirmedEvent(
                order.getId(),
                order.getCustomerId(),
                order.getTotalAmount(),
                order.getCurrency(),
                transactionId,
                Instant.now()
        );

        enqueueOutbox(order.getId(), "ORDER_CONFIRMED", event, correlationId);
    }

    @Transactional
    public void onPaymentFailed(String orderId, String reason, String correlationId) {
        log.warn("Saga Step: Payment failed for order {}. Reason: {}. Initiating stock compensation.", orderId, reason);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.setStatus(OrderStatus.CANCELLED);
        order.setFailureReason("PAYMENT_FAILED: " + reason);
        orderRepository.save(order);

        // Enqueue compensating ReleaseInventoryCommand to outbox
        ReleaseInventoryCommand command = new ReleaseInventoryCommand(order.getId(), reason);
        enqueueOutbox(order.getId(), "RELEASE_INVENTORY_COMMAND", command, correlationId);
    }

    @Transactional
    public void onInventoryReleased(String orderId, String correlationId) {
        log.info("Saga Compensation Completed: Stock released for order {}. Emitting cancellation.", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderCancelledEvent event = new OrderCancelledEvent(
                order.getId(),
                order.getCustomerId(),
                "PAYMENT_FAILED_COMPENSATED",
                order.getFailureReason(),
                Instant.now()
        );

        enqueueOutbox(order.getId(), "ORDER_CANCELLED", event, correlationId);
    }

    private void enqueueOutbox(String aggregateId, String eventType, Object payload, String correlationId) {
        try {
            OutboxMessage outbox = OutboxMessage.builder()
                    .aggregateType("ORDER")
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .correlationId(correlationId)
                    .status("PENDING")
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event {} for order {}", eventType, aggregateId, e);
            throw new IllegalStateException("Outbox serialization failure", e);
        }
    }
}
