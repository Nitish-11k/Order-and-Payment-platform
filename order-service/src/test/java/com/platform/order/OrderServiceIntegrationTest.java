package com.platform.order;

import com.platform.order.dto.CreateOrderRequest;
import com.platform.order.dto.OrderItemRequest;
import com.platform.order.dto.OrderResponse;
import com.platform.order.dto.ShippingAddressRequest;
import com.platform.order.entity.OrderStatus;
import com.platform.order.outbox.OutboxMessage;
import com.platform.order.outbox.OutboxRepository;
import com.platform.order.repository.OrderRepository;
import com.platform.order.service.OrderService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("isDockerAvailable")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OrderServiceIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_db")
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
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("Testcontainers: Full order creation, Flyway execution, and Outbox persistence")
    void shouldPersistOrderAndOutboxInPostgres() {
        assertThat(orderService).isNotNull();

        CreateOrderRequest request = new CreateOrderRequest(
                "cust-integration-1",
                List.of(
                        new OrderItemRequest("SKU-MACBOOK-AIR", 1, new BigDecimal("1299.99")),
                        new OrderItemRequest("SKU-USB-C-ADAPTER", 2, new BigDecimal("29.99"))
                ),
                new ShippingAddressRequest("500 Tech Ave", "San Jose", "CA", "95112", "USA")
        );

        String idempotencyKey = "idemp-int-test-001";
        OrderResponse response = orderService.createOrder(request, idempotencyKey, "corr-int-001");

        assertThat(response.orderId()).isNotNull();
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("1359.97"));

        // Verify database persistence
        assertThat(orderRepository.findById(response.orderId())).isPresent();

        // Verify outbox message saved
        List<OutboxMessage> outboxList = outboxRepository.findAll();
        assertThat(outboxList).anyMatch(m -> m.getAggregateId().equals(response.orderId()) && m.getStatus().equals("PENDING"));
    }
}
