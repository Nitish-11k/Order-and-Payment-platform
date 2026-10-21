package com.platform.order.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherSchedulerTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisherScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublisherScheduler(outboxRepository, kafkaTemplate);
        ReflectionTestUtils.setField(scheduler, "orderEventsTopic", "orders.v1.events");
        ReflectionTestUtils.setField(scheduler, "batchSize", 50);
    }

    @Test
    @DisplayName("publishPendingOutboxMessages: Polling picks pending messages, sends to Kafka and marks PUBLISHED")
    void shouldPollPendingAndPublishToKafka() {
        OutboxMessage msg = OutboxMessage.builder()
                .id(UUID.randomUUID())
                .aggregateType("ORDER")
                .aggregateId("ord-100")
                .eventType("ORDER_CREATED")
                .payload("{\"orderId\":\"ord-100\"}")
                .status("PENDING")
                .createdAt(Instant.now())
                .build();

        when(outboxRepository.findPendingMessages(any(Pageable.class))).thenReturn(List.of(msg));
        when(kafkaTemplate.send(eq("orders.v1.events"), eq("ord-100"), eq("{\"orderId\":\"ord-100\"}")))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler.publishPendingOutboxMessages();

        verify(kafkaTemplate, times(1)).send(eq("orders.v1.events"), eq("ord-100"), eq("{\"orderId\":\"ord-100\"}"));
        assertThat(msg.getStatus()).isEqualTo("PUBLISHED");
        assertThat(msg.getPublishedAt()).isNotNull();
        verify(outboxRepository).save(msg);
    }
}
