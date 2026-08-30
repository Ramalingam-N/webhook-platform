package com.webhook.ingestion.listener;

import com.webhook.ingestion.event.OutboxCreatedEvent;
import com.webhook.ingestion.service.OutboxPublisherService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

    private final OutboxPublisherService publisherService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOutboxEvent(OutboxCreatedEvent event) {
        try {
            publisherService.publish(event);
        } catch (Exception e) {
            log.error("Instant publish failed for event {}. Sweeper will retry.", event.eventId(), e);
        }
    }
}