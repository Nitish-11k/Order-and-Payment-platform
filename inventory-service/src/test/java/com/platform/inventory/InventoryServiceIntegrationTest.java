package com.platform.inventory;

import com.platform.inventory.dto.InventoryResponse;
import com.platform.inventory.dto.ItemReservationRequest;
import com.platform.inventory.dto.ReserveStockRequest;
import com.platform.inventory.dto.ReserveStockResponse;
import com.platform.inventory.entity.ReservationStatus;
import com.platform.inventory.exception.InsufficientStockException;
import com.platform.inventory.outbox.OutboxMessage;
import com.platform.inventory.outbox.OutboxRepository;
import com.platform.inventory.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf("isDockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class InventoryServiceIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_db")
            .withUsername("testuser")
            .withPassword("testpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("Testcontainers: Validates seed catalog, stock reservation, and outbox persistence")
    void shouldReserveSeededStockAndPersistOutbox() {
        assertThat(inventoryService).isNotNull();

        // 1. Verify seed data is loaded by Flyway
        InventoryResponse macbook = inventoryService.getInventoryBySku("SKU-MACBOOK-AIR");
        assertThat(macbook.availableQuantity()).isEqualTo(50);

        // 2. Reserve from the last item product
        ReserveStockRequest request = new ReserveStockRequest(
                "ord-int-stock-1",
                List.of(new ItemReservationRequest("SKU-LAST-ITEM-TEST", 1))
        );
        ReserveStockResponse response = inventoryService.reserveStock(request, "corr-stock-1");
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);

        // 3. Second reservation of the last item must fail
        ReserveStockRequest request2 = new ReserveStockRequest(
                "ord-int-stock-2",
                List.of(new ItemReservationRequest("SKU-LAST-ITEM-TEST", 1))
        );
        assertThatThrownBy(() -> inventoryService.reserveStock(request2, "corr-stock-2"))
                .isInstanceOf(InsufficientStockException.class);

        // 4. Verify outbox event exists
        List<OutboxMessage> outboxList = outboxRepository.findAll();
        assertThat(outboxList).anyMatch(m -> m.getAggregateId().equals("ord-int-stock-1") && m.getEventType().equals("INVENTORY_RESERVED"));
    }
}
