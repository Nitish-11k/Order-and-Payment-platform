package com.platform.inventory.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.inventory.dto.ReserveStockRequest;
import com.platform.inventory.dto.ReserveStockResponse;
import com.platform.inventory.entity.ProcessedEvent;
import com.platform.inventory.entity.ReservationStatus;
import com.platform.inventory.exception.InsufficientStockException;
import com.platform.inventory.outbox.OutboxMessage;
import com.platform.inventory.outbox.OutboxRepository;
import com.platform.inventory.repository.ProcessedEventRepository;
import com.platform.inventory.service.InventoryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryOrderConsumerTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private InventoryOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new InventoryOrderConsumer(inventoryService, processedEventRepository, outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("consumeOrderEvent: Happy path reserves stock, records processed_events and acknowledges")
    void shouldReserveStockAndAcknowledge() {
        String jsonPayload = """
                {
                    "eventType": "ORDER_CREATED",
                    "eventId": "evt-order-1",
                    "payload": {
                        "orderId": "ord-100",
                        "customerId": "cust-1",
                        "items": [
                            {"sku": "SKU-MACBOOK", "quantity": 1, "unitPrice": 1000.00}
                        ]
                    }
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.v1.events", 0, 100L, "ord-100", jsonPayload);

        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup("evt-order-1", "inventory-service-group"))
                .thenReturn(false);
        when(inventoryService.reserveStock(any(ReserveStockRequest.class), eq("evt-order-1")))
                .thenReturn(new ReserveStockResponse("ord-100", ReservationStatus.RESERVED, List.of(), Instant.now()));

        consumer.consumeOrderEvent(record, acknowledgment);

        verify(inventoryService).reserveStock(any(ReserveStockRequest.class), eq("evt-order-1"));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consumeOrderEvent: Skips already processed event (Idempotent Consumer)")
    void shouldSkipDuplicateEvent() {
        String jsonPayload = """
                {
                    "eventType": "ORDER_CREATED",
                    "eventId": "evt-duplicate-1",
                    "payload": {
                        "orderId": "ord-100",
                        "items": []
                    }
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.v1.events", 0, 101L, "ord-100", jsonPayload);

        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup("evt-duplicate-1", "inventory-service-group"))
                .thenReturn(true);

        consumer.consumeOrderEvent(record, acknowledgment);

        verify(inventoryService, never()).reserveStock(any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("consumeOrderEvent: Insufficient stock emits INVENTORY_RESERVATION_FAILED compensation event")
    void shouldEmitReservationFailedOnOutOfStock() {
        String jsonPayload = """
                {
                    "eventType": "ORDER_CREATED",
                    "eventId": "evt-order-out-of-stock",
                    "payload": {
                        "orderId": "ord-102",
                        "items": [
                            {"sku": "SKU-RARE", "quantity": 5}
                        ]
                    }
                }
                """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.v1.events", 0, 102L, "ord-102", jsonPayload);

        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup("evt-order-out-of-stock", "inventory-service-group"))
                .thenReturn(false);
        doThrow(new InsufficientStockException("SKU-RARE", 5, 0))
                .when(inventoryService).reserveStock(any(ReserveStockRequest.class), eq("evt-order-out-of-stock"));

        consumer.consumeOrderEvent(record, acknowledgment);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("INVENTORY_RESERVATION_FAILED");
        assertThat(captor.getValue().getAggregateId()).isEqualTo("ord-102");
        verify(acknowledgment).acknowledge();
    }
}
