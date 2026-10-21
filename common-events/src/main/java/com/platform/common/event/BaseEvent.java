package com.platform.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard CloudEvents-compliant event envelope.
 */
public record BaseEvent<T>(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Instant timestamp,
        String correlationId,
        String traceId,
        String schemaVersion,
        T payload
) {
    public static <T> BaseEvent<T> of(
            String eventType,
            String aggregateType,
            String aggregateId,
            String correlationId,
            String traceId,
            T payload
    ) {
        return new BaseEvent<>(
                UUID.randomUUID(),
                eventType,
                aggregateType,
                aggregateId,
                Instant.now(),
                correlationId != null ? correlationId : UUID.randomUUID().toString(),
                traceId,
                "1.0",
                payload
        );
    }
}
