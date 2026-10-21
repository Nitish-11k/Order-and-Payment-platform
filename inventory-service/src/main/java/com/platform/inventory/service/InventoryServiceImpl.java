package com.platform.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.event.inventory.InventoryReleasedEvent;
import com.platform.common.event.inventory.InventoryReservedEvent;
import com.platform.inventory.dto.InventoryResponse;
import com.platform.inventory.dto.ItemReservationRequest;
import com.platform.inventory.dto.ReleaseStockRequest;
import com.platform.inventory.dto.ReserveStockRequest;
import com.platform.inventory.dto.ReserveStockResponse;
import com.platform.inventory.entity.Inventory;
import com.platform.inventory.entity.InventoryReservation;
import com.platform.inventory.entity.ReservationStatus;
import com.platform.inventory.exception.InsufficientStockException;
import com.platform.inventory.exception.ProductNotFoundException;
import com.platform.inventory.outbox.OutboxMessage;
import com.platform.inventory.outbox.OutboxRepository;
import com.platform.inventory.repository.InventoryRepository;
import com.platform.inventory.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class InventoryServiceImpl implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public InventoryServiceImpl(
            InventoryRepository inventoryRepository,
            ReservationRepository reservationRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryResponse getInventoryBySku(String sku) {
        Inventory inventory = inventoryRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException(sku));

        return new InventoryResponse(
                inventory.getSku(),
                inventory.getProductName(),
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity(),
                inventory.getVersion()
        );
    }

    @Override
    @Transactional
    public ReserveStockResponse reserveStock(ReserveStockRequest request, String correlationId) {
        log.info("Processing stock reservation for order: {}", request.orderId());

        List<ItemReservationRequest> reservedItems = new ArrayList<>();

        for (ItemReservationRequest item : request.items()) {
            Inventory inventory = inventoryRepository.findBySku(item.sku())
                    .orElseThrow(() -> new ProductNotFoundException(item.sku()));

            if (!inventory.canReserve(item.quantity())) {
                log.warn("Cannot reserve stock: SKU {} requested {} but available {}",
                        item.sku(), item.quantity(), inventory.getAvailableQuantity());
                throw new InsufficientStockException(item.sku(), item.quantity(), inventory.getAvailableQuantity());
            }

            // Deduct available, increment reserved (optimistic locking checked on commit via @Version)
            inventory.reserve(item.quantity());
            inventoryRepository.save(inventory);

            // Record individual reservation entity
            InventoryReservation reservation = InventoryReservation.builder()
                    .orderId(request.orderId())
                    .sku(item.sku())
                    .quantity(item.quantity())
                    .status(ReservationStatus.RESERVED)
                    .build();
            reservationRepository.save(reservation);

            reservedItems.add(item);
        }

        Instant reservedUntil = Instant.now().plus(15, ChronoUnit.MINUTES);

        // Record INVENTORY_RESERVED outbox event in the same ACID transaction
        InventoryReservedEvent event = new InventoryReservedEvent(
                request.orderId(),
                reservedItems.stream()
                        .map(i -> new InventoryReservedEvent.ReservedItem(i.sku(), i.quantity()))
                        .toList(),
                reservedUntil
        );

        try {
            OutboxMessage outbox = OutboxMessage.builder()
                    .aggregateType("INVENTORY")
                    .aggregateId(request.orderId())
                    .eventType("INVENTORY_RESERVED")
                    .payload(objectMapper.writeValueAsString(event))
                    .correlationId(correlationId)
                    .status("PENDING")
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event for order: {}", request.orderId(), e);
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }

        log.info("Reserved stock successfully for order: {} ({} items)", request.orderId(), reservedItems.size());
        return new ReserveStockResponse(request.orderId(), ReservationStatus.RESERVED, reservedItems, Instant.now());
    }

    @Override
    @Transactional
    public void releaseStock(ReleaseStockRequest request, String correlationId) {
        log.info("Compensating: Releasing stock reservations for order: {}", request.orderId());

        List<InventoryReservation> reservations = reservationRepository.findByOrderId(request.orderId());
        List<InventoryReleasedEvent.ReleasedItem> releasedItems = new ArrayList<>();

        for (InventoryReservation res : reservations) {
            if (res.getStatus() == ReservationStatus.RESERVED) {
                Inventory inventory = inventoryRepository.findBySku(res.getSku())
                        .orElseThrow(() -> new ProductNotFoundException(res.getSku()));

                inventory.release(res.getQuantity());
                inventoryRepository.save(inventory);

                res.setStatus(ReservationStatus.RELEASED);
                reservationRepository.save(res);

                releasedItems.add(new InventoryReleasedEvent.ReleasedItem(res.getSku(), res.getQuantity()));
            }
        }

        // Record INVENTORY_RELEASED outbox event
        InventoryReleasedEvent event = new InventoryReleasedEvent(request.orderId(), releasedItems);
        try {
            OutboxMessage outbox = OutboxMessage.builder()
                    .aggregateType("INVENTORY")
                    .aggregateId(request.orderId())
                    .eventType("INVENTORY_RELEASED")
                    .payload(objectMapper.writeValueAsString(event))
                    .correlationId(correlationId)
                    .status("PENDING")
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox release event for order: {}", request.orderId(), e);
            throw new IllegalStateException("Failed to serialize outbox release payload", e);
        }

        log.info("Stock released successfully for order: {} ({} items released)", request.orderId(), releasedItems.size());
    }
}
