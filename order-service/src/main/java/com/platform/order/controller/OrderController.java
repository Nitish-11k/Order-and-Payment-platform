package com.platform.order.controller;

import com.platform.order.dto.CreateOrderRequest;
import com.platform.order.dto.OrderResponse;
import com.platform.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order lifecycle management and Saga checkout API")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(
            summary = "Create a new Order",
            description = "Creates an order in PENDING status, enqueues an outbox event, and initiates the checkout saga.",
            responses = {
                    @ApiResponse(
                            responseCode = "201",
                            description = "Order created successfully",
                            headers = @Header(name = "Location", description = "URI of the newly created order")
                    ),
                    @ApiResponse(responseCode = "400", description = "Validation failed or missing idempotency header"),
                    @ApiResponse(responseCode = "409", description = "Idempotency key conflict with mismatching payload")
            }
    )
    public ResponseEntity<OrderResponse> createOrder(
            @Parameter(description = "Unique client idempotency key (UUIDv4 recommended)", required = true)
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,

            @Parameter(description = "Distributed trace correlation ID")
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,

            @Valid @RequestBody CreateOrderRequest request
    ) {
        String effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        OrderResponse response = orderService.createOrder(request, idempotencyKey, effectiveCorrelationId);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.orderId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get Order by ID", description = "Retrieves order status, line items, and current saga state.")
    public ResponseEntity<OrderResponse> getOrderById(
            @Parameter(description = "Order ID", required = true)
            @PathVariable String orderId
    ) {
        OrderResponse response = orderService.getOrderById(orderId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "List Orders", description = "Query orders filtered by customer ID.")
    public ResponseEntity<List<OrderResponse>> listOrders(
            @Parameter(description = "Filter orders by customer ID")
            @RequestParam(value = "customerId", required = false) String customerId
    ) {
        if (customerId != null && !customerId.isBlank()) {
            return ResponseEntity.ok(orderService.getOrdersByCustomerId(customerId));
        }
        return ResponseEntity.ok(List.of());
    }
}
