package com.platform.payment.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.payment.dto.PaymentRequest;
import com.platform.payment.entity.ProcessedEvent;
import com.platform.payment.entity.ProcessedEventId;
import com.platform.payment.exception.PaymentDeclinedException;
import com.platform.payment.repository.ProcessedEventRepository;
import com.platform.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class PaymentOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderConsumer.class);
    private static final String CONSUMER_GROUP = "payment-service-group";

    private final PaymentService paymentService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentOrderConsumer(
            PaymentService paymentService,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper
    ) {
        this.paymentService = paymentService;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.order-events:orders.v1.events}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumePaymentTrigger(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String payload = record.value();
        log.info("Received payment trigger record with key {}: {}", record.key(), payload);

        try {
            JsonNode rootNode = objectMapper.readTree(payload);

            String eventType = null;
            String eventId = null;
            JsonNode payloadNode = null;

            if (rootNode.has("eventType") && rootNode.has("payload")) {
                eventType = rootNode.get("eventType").asText();
                eventId = rootNode.has("eventId") ? rootNode.get("eventId").asText() : UUID.randomUUID().toString();
                payloadNode = rootNode.get("payload");
            } else if (rootNode.has("amount") && rootNode.has("orderId")) {
                eventType = "PAYMENT_TRIGGERED";
                eventId = record.key() + "-PAYMENT_TRIGGERED";
                payloadNode = rootNode;
            }

            if (!"PAYMENT_TRIGGERED".equalsIgnoreCase(eventType)) {
                // Ignore events not meant for payment
                ack.acknowledge();
                return;
            }

            // 1. Idempotency Check
            if (processedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, CONSUMER_GROUP)) {
                log.info("Payment event {} already processed. Skipping.", eventId);
                ack.acknowledge();
                return;
            }

            // 2. Process Payment
            String orderId = payloadNode.get("orderId").asText();
            String customerId = payloadNode.get("customerId").asText();
            BigDecimal amount = new BigDecimal(payloadNode.get("amount").asText());
            String currency = payloadNode.has("currency") ? payloadNode.get("currency").asText() : "USD";

            PaymentRequest request = new PaymentRequest(orderId, customerId, amount, currency);
            String idempotencyKey = "idemp-saga-pay-" + orderId;

            try {
                paymentService.processPayment(request, idempotencyKey, eventId);
            } catch (PaymentDeclinedException ex) {
                log.warn("Payment declined during saga for order {}: {}", orderId, ex.getMessage());
                // The payment service already persisted FAILED payment and PAYMENT_FAILED outbox event
            }

            // 3. Mark processed
            processedEventRepository.save(new ProcessedEvent(
                    new ProcessedEventId(eventId, CONSUMER_GROUP),
                    eventType
            ));

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error consuming payment trigger: {}", e.getMessage(), e);
            throw new RuntimeException("Triggering Spring Kafka retry/DLT", e);
        }
    }
}
