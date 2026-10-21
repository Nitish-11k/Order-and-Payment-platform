package com.platform.inventory.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.topics.inventory-events:inventory.v1.events}")
    private String inventoryEventsTopic;

    @Value("${app.outbox.poller.batch-size:50}")
    private int batchSize;

    public OutboxPublisherScheduler(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poller.interval-ms:1000}")
    @Transactional
    public void publishPendingOutboxMessages() {
        List<OutboxMessage> pendingMessages = outboxRepository.findPendingMessages(PageRequest.of(0, batchSize));

        if (pendingMessages.isEmpty()) {
            return;
        }

        for (OutboxMessage message : pendingMessages) {
            String topic = inventoryEventsTopic;
            String partitionKey = message.getAggregateId();

            try {
                kafkaTemplate.send(topic, partitionKey, message.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                message.setStatus("PUBLISHED");
                                message.setPublishedAt(Instant.now());
                                outboxRepository.save(message);
                                log.info("Successfully published inventory outbox event [{}] for order {}",
                                        message.getEventType(), message.getAggregateId());
                            } else {
                                message.setRetryCount(message.getRetryCount() + 1);
                                if (message.getRetryCount() >= 5) {
                                    message.setStatus("FAILED");
                                    log.error("Inventory outbox message [{}] failed after max retries: {}", message.getId(), ex.getMessage());
                                }
                                outboxRepository.save(message);
                            }
                        });
            } catch (Exception e) {
                log.error("Synchronous error enqueuing inventory outbox message {} to Kafka: {}", message.getId(), e.getMessage());
                message.setRetryCount(message.getRetryCount() + 1);
                outboxRepository.save(message);
            }
        }
    }
}
