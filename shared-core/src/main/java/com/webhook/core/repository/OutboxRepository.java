package com.webhook.core.repository;

import com.webhook.core.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {
    // The poller will use this to find events that haven't been sent to Kafka yet
    List findByPublishedFalse();
}