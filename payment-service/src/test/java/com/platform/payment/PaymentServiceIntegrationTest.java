package com.platform.payment;

import com.platform.payment.dto.PaymentRequest;
import com.platform.payment.dto.PaymentResponse;
import com.platform.payment.entity.PaymentStatus;
import com.platform.payment.outbox.OutboxMessage;
import com.platform.payment.outbox.OutboxRepository;
import com.platform.payment.repository.PaymentRepository;
import com.platform.payment.service.PaymentService;
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
class PaymentServiceIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
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
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("Testcontainers: Full payment processing, Flyway execution, and Outbox persistence")
    void shouldPersistPaymentAndOutboxInPostgres() {
        assertThat(paymentService).isNotNull();

        PaymentRequest request = new PaymentRequest(
                "ord-int-pay-1",
                "cust-integration-pay",
                new BigDecimal("249.99"),
                "USD"
        );

        String idempotencyKey = "idemp-int-pay-001";
        PaymentResponse response = paymentService.processPayment(request, idempotencyKey, "corr-int-pay-1");

        assertThat(response.paymentId()).isNotNull();
        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(response.providerTransactionId()).isNotNull();

        // Verify database persistence
        assertThat(paymentRepository.findById(response.paymentId())).isPresent();

        // Verify outbox message saved
        List<OutboxMessage> outboxList = outboxRepository.findAll();
        assertThat(outboxList).anyMatch(m -> m.getAggregateId().equals("ord-int-pay-1") && m.getEventType().equals("PAYMENT_COMPLETED"));
    }
}
