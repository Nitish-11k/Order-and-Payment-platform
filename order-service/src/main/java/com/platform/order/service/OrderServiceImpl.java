package com.platform.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.event.order.OrderCreatedEvent;
import com.platform.order.dto.CreateOrderRequest;
import com.platform.order.dto.OrderItemResponse;
import com.platform.order.dto.OrderResponse;
import com.platform.order.entity.Order;
import com.platform.order.entity.OrderItem;
import com.platform.order.entity.OrderStatus;
import com.platform.order.exception.DuplicateIdempotencyKeyException;
import com.platform.order.exception.OrderNotFoundException;
import com.platform.order.outbox.OutboxMessage;
import com.platform.order.outbox.OutboxRepository;
import com.platform.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderServiceImpl(
            OrderRepository orderRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey, String correlationId) {
        log.info("Processing order creation for customer: {} with idempotencyKey: {}", request.customerId(), idempotencyKey);

        // 1. Idempotency Check
        Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOrder.isPresent()) {
            Order order = existingOrder.get();
            if (order.getCustomerId().equals(request.customerId())) {
                log.info("Idempotent request detected. Returning existing order: {}", order.getId());
                return mapToResponse(order);
            } else {
                throw new DuplicateIdempotencyKeyException(idempotencyKey);
            }
        }

        // 2. Compute total amount
        BigDecimal totalAmount = request.items().stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. Build & Save Order Entity
        String orderId = "ord-" + UUID.randomUUID().toString().substring(0, 8);
        Order order = Order.builder()
                .id(orderId)
                .customerId(request.customerId())
                .totalAmount(totalAmount)
                .currency("USD")
                .status(OrderStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();

        request.items().forEach(itemReq -> {
            OrderItem item = OrderItem.builder()
                    .sku(itemReq.sku())
                    .quantity(itemReq.quantity())
                    .unitPrice(itemReq.unitPrice())
                    .build();
            order.addItem(item);
        });

        Order savedOrder = orderRepository.save(order);

        // 4. Record Outbox Event in the same ACID transaction
        OrderCreatedEvent eventPayload = new OrderCreatedEvent(
                savedOrder.getId(),
                savedOrder.getCustomerId(),
                savedOrder.getTotalAmount(),
                savedOrder.getCurrency(),
                request.items().stream()
                        .map(i -> new OrderCreatedEvent.OrderItemPayload(i.sku(), i.quantity(), i.unitPrice()))
                        .toList(),
                new OrderCreatedEvent.ShippingAddressPayload(
                        request.shippingAddress().street(),
                        request.shippingAddress().city(),
                        request.shippingAddress().state(),
                        request.shippingAddress().postalCode(),
                        request.shippingAddress().country()
                )
        );

        try {
            String payloadJson = objectMapper.writeValueAsString(eventPayload);
            OutboxMessage outboxMessage = OutboxMessage.builder()
                    .aggregateType("ORDER")
                    .aggregateId(savedOrder.getId())
                    .eventType("ORDER_CREATED")
                    .payload(payloadJson)
                    .correlationId(correlationId)
                    .status("PENDING")
                    .build();
            outboxRepository.save(outboxMessage);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OrderCreatedEvent to JSON for order: {}", savedOrder.getId(), e);
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }

        log.info("Order created successfully: {} with status: {}", savedOrder.getId(), savedOrder.getStatus());
        return mapToResponse(savedOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(String orderId) {
        return orderRepository.findById(orderId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByCustomerId(String customerId) {
        return orderRepository.findByCustomerId(customerId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getSku(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                ))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getIdempotencyKey(),
                order.getFailureReason(),
                order.getVersion(),
                itemResponses,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
