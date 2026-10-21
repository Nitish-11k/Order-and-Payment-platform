package com.platform.payment.controller;

import com.platform.payment.dto.PaymentRequest;
import com.platform.payment.dto.PaymentResponse;
import com.platform.payment.dto.RefundRequest;
import com.platform.payment.dto.RefundResponse;
import com.platform.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment execution, Resilience4j circuit breaking, and refund APIs")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/charge")
    @Operation(
            summary = "Process Payment",
            description = "Executes an idempotent payment charge through the Resilience4j-protected mock provider and writes outbox event.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Payment completed successfully"),
                    @ApiResponse(responseCode = "422", description = "Payment declined (insufficient balance or fraud rule)"),
                    @ApiResponse(responseCode = "503", description = "Payment provider unavailable (Circuit Breaker OPEN)")
            }
    )
    public ResponseEntity<PaymentResponse> charge(
            @Parameter(description = "Unique client idempotency key (UUIDv4)", required = true)
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,

            @Parameter(description = "Distributed trace correlation ID")
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,

            @Valid @RequestBody PaymentRequest request
    ) {
        String effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        PaymentResponse response = paymentService.processPayment(request, idempotencyKey, effectiveCorrelationId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get Payment by ID")
    public ResponseEntity<PaymentResponse> getPaymentById(
            @Parameter(description = "Payment ID", required = true)
            @PathVariable String paymentId
    ) {
        return ResponseEntity.ok(paymentService.getPaymentById(paymentId));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Get Payment by Order ID")
    public ResponseEntity<PaymentResponse> getPaymentByOrderId(
            @Parameter(description = "Associated Order ID", required = true)
            @PathVariable String orderId
    ) {
        return ResponseEntity.ok(paymentService.getPaymentByOrderId(orderId));
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund Payment", description = "Initiates a refund for compensation workflows.")
    public ResponseEntity<RefundResponse> refundPayment(
            @Parameter(description = "Payment ID to refund", required = true)
            @PathVariable String paymentId,

            @Parameter(description = "Distributed trace correlation ID")
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,

            @Valid @RequestBody RefundRequest request
    ) {
        String effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        RefundResponse response = paymentService.refundPayment(paymentId, request, effectiveCorrelationId);
        return ResponseEntity.ok(response);
    }
}
