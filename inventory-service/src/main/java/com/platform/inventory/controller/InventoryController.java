package com.platform.inventory.controller;

import com.platform.inventory.dto.InventoryResponse;
import com.platform.inventory.dto.ReleaseStockRequest;
import com.platform.inventory.dto.ReserveStockRequest;
import com.platform.inventory.dto.ReserveStockResponse;
import com.platform.inventory.service.InventoryService;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Stock catalog, optimistic locking reservation, and compensation APIs")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{sku}")
    @Operation(summary = "Get Inventory by SKU", description = "Retrieves real-time stock levels and version for a given product SKU.")
    public ResponseEntity<InventoryResponse> getInventoryBySku(
            @Parameter(description = "Product SKU code", required = true)
            @PathVariable String sku
    ) {
        return ResponseEntity.ok(inventoryService.getInventoryBySku(sku));
    }

    @PostMapping("/reserve")
    @Operation(
            summary = "Reserve Stock",
            description = "Reserves product quantities using optimistic locking and logs an outbox event.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Stock reserved successfully"),
                    @ApiResponse(responseCode = "404", description = "Product SKU not found"),
                    @ApiResponse(responseCode = "409", description = "Insufficient stock or concurrency version conflict")
            }
    )
    public ResponseEntity<ReserveStockResponse> reserveStock(
            @Parameter(description = "Distributed trace correlation ID")
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,

            @Valid @RequestBody ReserveStockRequest request
    ) {
        String effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        ReserveStockResponse response = inventoryService.reserveStock(request, effectiveCorrelationId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/release")
    @Operation(
            summary = "Release Stock (Compensation)",
            description = "Releases previously reserved stock for an order back to available inventory."
    )
    public ResponseEntity<Map<String, String>> releaseStock(
            @Parameter(description = "Distributed trace correlation ID")
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,

            @Valid @RequestBody ReleaseStockRequest request
    ) {
        String effectiveCorrelationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        inventoryService.releaseStock(request, effectiveCorrelationId);
        return ResponseEntity.ok(Map.of(
                "orderId", request.orderId(),
                "status", "RELEASED",
                "message", "Stock reservations successfully released."
        ));
    }
}
