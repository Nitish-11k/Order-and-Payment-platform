package com.platform.order.service;

import com.platform.order.dto.CreateOrderRequest;
import com.platform.order.dto.OrderResponse;

import java.util.List;

public interface OrderService {

    OrderResponse createOrder(CreateOrderRequest request, String idempotencyKey, String correlationId);

    OrderResponse getOrderById(String orderId);

    List<OrderResponse> getOrdersByCustomerId(String customerId);
}
