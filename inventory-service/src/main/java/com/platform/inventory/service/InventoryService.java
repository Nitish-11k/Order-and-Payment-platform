package com.platform.inventory.service;

import com.platform.inventory.dto.InventoryResponse;
import com.platform.inventory.dto.ReleaseStockRequest;
import com.platform.inventory.dto.ReserveStockRequest;
import com.platform.inventory.dto.ReserveStockResponse;

public interface InventoryService {

    InventoryResponse getInventoryBySku(String sku);

    ReserveStockResponse reserveStock(ReserveStockRequest request, String correlationId);

    void releaseStock(ReleaseStockRequest request, String correlationId);
}
