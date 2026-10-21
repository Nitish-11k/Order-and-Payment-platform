package com.platform.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryServiceImpl(
                inventoryRepository,
                reservationRepository,
                outboxRepository,
                objectMapper
        );
    }

    @Test
    @DisplayName("getInventoryBySku: Returns inventory details when SKU exists")
    void shouldReturnInventoryDetails() {
        Inventory inventory = Inventory.builder()
                .sku("SKU-TEST")
                .productName("Test Product")
                .availableQuantity(10)
                .reservedQuantity(2)
                .version(1L)
                .build();

        when(inventoryRepository.findBySku("SKU-TEST")).thenReturn(Optional.of(inventory));

        InventoryResponse response = inventoryService.getInventoryBySku("SKU-TEST");

        assertThat(response.sku()).isEqualTo("SKU-TEST");
        assertThat(response.availableQuantity()).isEqualTo(10);
        assertThat(response.reservedQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("reserveStock: Successfully reserves stock and creates Outbox event")
    void shouldReserveStockAndPersistOutbox() {
        Inventory inventory = Inventory.builder()
                .sku("SKU-MACBOOK")
                .productName("MacBook Air")
                .availableQuantity(5)
                .reservedQuantity(0)
                .version(0L)
                .build();

        when(inventoryRepository.findBySku("SKU-MACBOOK")).thenReturn(Optional.of(inventory));

        ReserveStockRequest request = new ReserveStockRequest(
                "ord-123",
                List.of(new ItemReservationRequest("SKU-MACBOOK", 2))
        );

        ReserveStockResponse response = inventoryService.reserveStock(request, "corr-100");

        assertThat(response.orderId()).isEqualTo("ord-123");
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(3);
        assertThat(inventory.getReservedQuantity()).isEqualTo(2);

        // Verify outbox event saved
        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("INVENTORY_RESERVED");
        assertThat(captor.getValue().getAggregateId()).isEqualTo("ord-123");
    }

    @Test
    @DisplayName("reserveStock: Throws InsufficientStockException when requested > available")
    void shouldThrowInsufficientStockWhenStockDepleted() {
        Inventory inventory = Inventory.builder()
                .sku("SKU-MACBOOK")
                .productName("MacBook Air")
                .availableQuantity(1)
                .reservedQuantity(0)
                .version(0L)
                .build();

        when(inventoryRepository.findBySku("SKU-MACBOOK")).thenReturn(Optional.of(inventory));

        ReserveStockRequest request = new ReserveStockRequest(
                "ord-124",
                List.of(new ItemReservationRequest("SKU-MACBOOK", 2))
        );

        assertThatThrownBy(() -> inventoryService.reserveStock(request, "corr-101"))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("SKU-MACBOOK");

        verify(outboxRepository, never()).save(any(OutboxMessage.class));
    }

    @Test
    @DisplayName("releaseStock: Releases reserved items and logs compensation outbox event")
    void shouldReleaseReservedStock() {
        Inventory inventory = Inventory.builder()
                .sku("SKU-MACBOOK")
                .productName("MacBook Air")
                .availableQuantity(3)
                .reservedQuantity(2)
                .version(0L)
                .build();

        InventoryReservation reservation = InventoryReservation.builder()
                .orderId("ord-125")
                .sku("SKU-MACBOOK")
                .quantity(2)
                .status(ReservationStatus.RESERVED)
                .build();

        when(reservationRepository.findByOrderId("ord-125")).thenReturn(List.of(reservation));
        when(inventoryRepository.findBySku("SKU-MACBOOK")).thenReturn(Optional.of(inventory));

        inventoryService.releaseStock(new ReleaseStockRequest("ord-125"), "corr-102");

        assertThat(inventory.getAvailableQuantity()).isEqualTo(5);
        assertThat(inventory.getReservedQuantity()).isEqualTo(0);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("INVENTORY_RELEASED");
    }
}
