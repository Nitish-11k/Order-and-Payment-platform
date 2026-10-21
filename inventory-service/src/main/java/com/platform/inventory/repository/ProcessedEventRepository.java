package com.platform.inventory.repository;

import com.platform.inventory.entity.ProcessedEvent;
import com.platform.inventory.entity.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
    boolean existsByIdEventIdAndIdConsumerGroup(String eventId, String consumerGroup);
}
