package com.platform.order.repository;

import com.platform.order.entity.ProcessedEvent;
import com.platform.order.entity.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
    boolean existsByIdEventIdAndIdConsumerGroup(String eventId, String consumerGroup);
}
