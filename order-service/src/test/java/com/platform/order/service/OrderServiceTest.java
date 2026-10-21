package com.platform.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.order.dto.CreateOrderRequest;
import com.platform.order.dto.OrderItemRequest;
import com.platform.order.dto.OrderResponse;
import com.platform.order.dto.ShippingAddressRequest;
import com.platform.order.entity.Order;
import com.platform.order.entity.OrderStatus;
import com.platform.order.exception.DuplicateIdempotencyKeyException;
import com.platform.order.exception.OrderNotFoundException;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderRepository, outboxRepository, objectMapper);
    }

    private CreateOrderRequest createSampleRequest(String customerId) {
        return new CreateOrderRequest(
                customerId,
                List.of(
                        new OrderItemRequest("SKU-MACBOOK", 1, new BigDecimal("1000.00")),
                        new OrderItemRequest("SKU-MOUSE", 2, new BigDecimal("50.00"))
                ),
                new ShippingAddressRequest("123 Main St", "Springfield", "IL", "62701", "USA")
        );
    }

    @Test
    @DisplayName("createOrder: Successfully saves order and inserts outbox event")
    void shouldCreateOrderAndPersistOutbox() {
        CreateOrderRequest request = createSampleRequest("cust-100");
        String idempotencyKey = "idemp-001";
        String correlationId = "corr-001";

        when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, idempotencyKey, correlationId);

        assertThat(response).isNotNull();
        assertThat(response.customerId()).isEqualTo("cust-100");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("1100.00"));
        assertThat(response.items()).hasSize(2);

        // Verify outbox entry created
        ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository, times(1)).save(outboxCaptor.capture());
        OutboxMessage outbox = outboxCaptor.getValue();
        assertThat(outbox.getAggregateType()).isEqualTo("ORDER");
        assertThat(outbox.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        assertThat(outbox.getPayload()).contains("SKU-MACBOOK");
    }

    @Test
    @DisplayName("createOrder: Replays existing order when same customer uses identical idempotency key")
    void shouldReturnExistingOrderForSameCustomerAndKey() {
        CreateOrderRequest request = createSampleRequest("cust-100");
        String idempotencyKey = "idemp-001";

        Order existingOrder = Order.builder()
                .id("ord-existing")
                .customerId("cust-100")
                .totalAmount(new BigDecimal("1100.00"))
                .currency("USD")
                .status(OrderStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();

        when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        OrderResponse response = orderService.createOrder(request, idempotencyKey, "corr-002");

        assertThat(response.orderId()).isEqualTo("ord-existing");
        verify(orderRepository, never()).save(any(Order.class));
        verify(outboxRepository, never()).save(any(OutboxMessage.class));
    }

    @Test
    @DisplayName("createOrder: Throws DuplicateIdempotencyKeyException when different customer uses existing key")
    void shouldThrowWhenDifferentCustomerUsesSameIdempotencyKey() {
        CreateOrderRequest request = createSampleRequest("cust-200");
        String idempotencyKey = "idemp-001";

        Order existingOrder = Order.builder()
                .id("ord-existing")
                .customerId("cust-100")
                .totalAmount(new BigDecimal("1100.00"))
                .currency("USD")
                .status(OrderStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();

        when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        assertThatThrownBy(() -> orderService.createOrder(request, idempotencyKey, "corr-003"))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining("idemp-001");
    }

    @Test
    @DisplayName("getOrderById: Throws OrderNotFoundException when order does not exist")
    void shouldThrowWhenOrderNotFound() {
        when(orderRepository.findById("ord-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderById("ord-999"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("ord-999");
    }
}
