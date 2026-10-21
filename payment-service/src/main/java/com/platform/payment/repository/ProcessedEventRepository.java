package com.platform.payment.repository;

import com.platform.payment.entity.ProcessedEvent;
import com.platform.payment.entity.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
    boolean existsByIdEventIdAndIdConsumerGroup(String eventId, String consumerGroup);
}
