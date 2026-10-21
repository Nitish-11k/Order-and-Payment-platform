package com.platform.order.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.order.entity.ProcessedEvent;
import com.platform.order.entity.ProcessedEventId;
import com.platform.order.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class OrderSagaConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaConsumer.class);
    private static final String CONSUMER_GROUP = "order-service-group";

    private final OrderSagaCoordinator sagaCoordinator;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public OrderSagaConsumer(
            OrderSagaCoordinator sagaCoordinator,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper
    ) {
        this.sagaCoordinator = sagaCoordinator;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.inventory-events:inventory.v1.events}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeInventoryReply(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String payload = record.value();
        log.info("Order saga received inventory event: {}", payload);

        try {
            JsonNode rootNode = objectMapper.readTree(payload);

            String eventType;
            String eventId;
            JsonNode payloadNode;

            if (rootNode.has("eventType") && rootNode.has("payload")) {
                eventType = rootNode.get("eventType").asText();
                eventId = rootNode.has("eventId") ? rootNode.get("eventId").asText() : UUID.randomUUID().toString();
                payloadNode = rootNode.get("payload");
            } else if (rootNode.has("reservations")) {
                eventType = "INVENTORY_RESERVED";
                eventId = record.key() + "-INVENTORY_RESERVED";
                payloadNode = rootNode;
            } else if (rootNode.has("failureReason")) {
                eventType = "INVENTORY_RESERVATION_FAILED";
                eventId = record.key() + "-INVENTORY_FAILED";
                payloadNode = rootNode;
            } else if (rootNode.has("releasedItems")) {
                eventType = "INVENTORY_RELEASED";
                eventId = record.key() + "-INVENTORY_RELEASED";
                payloadNode = rootNode;
            } else {
                ack.acknowledge();
                return;
            }

            // Deduplication
            if (processedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, CONSUMER_GROUP)) {
                log.info("Inventory event {} already processed by saga. Skipping.", eventId);
                ack.acknowledge();
                return;
            }

            String orderId = payloadNode.has("orderId") ? payloadNode.get("orderId").asText() : record.key();

            if ("INVENTORY_RESERVED".equalsIgnoreCase(eventType)) {
                sagaCoordinator.onInventoryReserved(orderId, eventId);
            } else if ("INVENTORY_RESERVATION_FAILED".equalsIgnoreCase(eventType)) {
                String reason = payloadNode.has("failureReason") ? payloadNode.get("failureReason").asText() : "Stock exhausted";
                sagaCoordinator.onInventoryReservationFailed(orderId, reason, eventId);
            } else if ("INVENTORY_RELEASED".equalsIgnoreCase(eventType)) {
                sagaCoordinator.onInventoryReleased(orderId, eventId);
            }

            processedEventRepository.save(new ProcessedEvent(
                    new ProcessedEventId(eventId, CONSUMER_GROUP),
                    eventType
            ));

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing inventory event in OrderSagaConsumer: {}", e.getMessage(), e);
            throw new RuntimeException("Triggering DLT for inventory event", e);
        }
    }

    @KafkaListener(
            topics = "${app.kafka.topics.payment-events:payments.v1.events}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumePaymentReply(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String payload = record.value();
        log.info("Order saga received payment event: {}", payload);

        try {
            JsonNode rootNode = objectMapper.readTree(payload);

            String eventType;
            String eventId;
            JsonNode payloadNode;

            if (rootNode.has("eventType") && rootNode.has("payload")) {
                eventType = rootNode.get("eventType").asText();
                eventId = rootNode.has("eventId") ? rootNode.get("eventId").asText() : UUID.randomUUID().toString();
                payloadNode = rootNode.get("payload");
            } else if (rootNode.has("providerTransactionId")) {
                eventType = "PAYMENT_COMPLETED";
                eventId = record.key() + "-PAYMENT_COMPLETED";
                payloadNode = rootNode;
            } else if (rootNode.has("errorCode") || rootNode.has("errorMessage")) {
                eventType = "PAYMENT_FAILED";
                eventId = record.key() + "-PAYMENT_FAILED";
                payloadNode = rootNode;
            } else {
                ack.acknowledge();
                return;
            }

            // Deduplication
            if (processedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, CONSUMER_GROUP)) {
                log.info("Payment event {} already processed by saga. Skipping.", eventId);
                ack.acknowledge();
                return;
            }

            String orderId = payloadNode.has("orderId") ? payloadNode.get("orderId").asText() : record.key();

            if ("PAYMENT_COMPLETED".equalsIgnoreCase(eventType)) {
                String txId = payloadNode.has("providerTransactionId") ? payloadNode.get("providerTransactionId").asText() : "mock-tx";
                sagaCoordinator.onPaymentCompleted(orderId, txId, eventId);
            } else if ("PAYMENT_FAILED".equalsIgnoreCase(eventType)) {
                String reason = payloadNode.has("errorMessage") ? payloadNode.get("errorMessage").asText() : "Declined";
                sagaCoordinator.onPaymentFailed(orderId, reason, eventId);
            }

            processedEventRepository.save(new ProcessedEvent(
                    new ProcessedEventId(eventId, CONSUMER_GROUP),
                    eventType
            ));

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing payment event in OrderSagaConsumer: {}", e.getMessage(), e);
            throw new RuntimeException("Triggering DLT for payment event", e);
        }
    }
}
