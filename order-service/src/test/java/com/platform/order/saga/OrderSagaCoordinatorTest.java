package com.platform.order.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.order.entity.Order;
import com.platform.order.entity.OrderStatus;
import com.platform.order.outbox.OutboxMessage;
import com.platform.order.outbox.OutboxRepository;
import com.platform.order.repository.OrderRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderSagaCoordinatorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private OrderSagaCoordinator coordinator;

    private Order testOrder;

    @BeforeEach
    void setUp() {
        coordinator = new OrderSagaCoordinator(orderRepository, outboxRepository, objectMapper);

        testOrder = Order.builder()
                .id("ord-100")
                .customerId("cust-1")
                .totalAmount(new BigDecimal("199.99"))
                .currency("USD")
                .status(OrderStatus.PENDING)
                .idempotencyKey("idemp-100")
                .build();
    }

    @Test
    @DisplayName("onInventoryReserved: Updates status to INVENTORY_RESERVED and enqueues PAYMENT_TRIGGERED")
    void shouldTransitionToInventoryReservedAndTriggerPayment() {
        when(orderRepository.findById("ord-100")).thenReturn(Optional.of(testOrder));

        coordinator.onInventoryReserved("ord-100", "corr-1");

        assertThat(testOrder.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        verify(orderRepository).save(testOrder);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PAYMENT_TRIGGERED");
        assertThat(captor.getValue().getAggregateId()).isEqualTo("ord-100");
    }

    @Test
    @DisplayName("onInventoryReservationFailed: Cancels order and enqueues ORDER_CANCELLED")
    void shouldCancelOrderOnInventoryFailure() {
        when(orderRepository.findById("ord-100")).thenReturn(Optional.of(testOrder));

        coordinator.onInventoryReservationFailed("ord-100", "Out of stock for SKU-MACBOOK", "corr-2");

        assertThat(testOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(testOrder.getFailureReason()).contains("INSUFFICIENT_STOCK");
        verify(orderRepository).save(testOrder);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ORDER_CANCELLED");
    }

    @Test
    @DisplayName("onPaymentCompleted: Confirms order and enqueues ORDER_CONFIRMED")
    void shouldConfirmOrderOnPaymentSuccess() {
        testOrder.setStatus(OrderStatus.INVENTORY_RESERVED);
        when(orderRepository.findById("ord-100")).thenReturn(Optional.of(testOrder));

        coordinator.onPaymentCompleted("ord-100", "tx-mock-9988", "corr-3");

        assertThat(testOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository).save(testOrder);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ORDER_CONFIRMED");
    }

    @Test
    @DisplayName("onPaymentFailed: Cancels order and initiates stock compensation with RELEASE_INVENTORY_COMMAND")
    void shouldInitiateCompensationOnPaymentFailure() {
        testOrder.setStatus(OrderStatus.INVENTORY_RESERVED);
        when(orderRepository.findById("ord-100")).thenReturn(Optional.of(testOrder));

        coordinator.onPaymentFailed("ord-100", "CARD_DECLINED", "corr-4");

        assertThat(testOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(testOrder);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("RELEASE_INVENTORY_COMMAND");
    }
}
