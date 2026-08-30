package com.webhook.ingestion.service;

import com.webhook.core.entity.Event;
import com.webhook.core.entity.Outbox;
import com.webhook.core.repository.EventRepository;
import com.webhook.core.repository.OutboxRepository;
import com.webhook.ingestion.event.OutboxCreatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventIngestionService {

    private final StringRedisTemplate redisTemplate;
    private final EventRepository eventRepository;
    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public UUID ingestEvent(String tenantId, String eventType, String payload, String idempotencyKey) {
        String redisKey = "idem:" + tenantId + ":" + idempotencyKey;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
            log.warn("Duplicate event (redis) tenant={} key={}", tenantId, idempotencyKey);
            throw new IllegalArgumentException("Duplicate event detected");
        }

        try {
            Event event = eventRepository.save(Event.builder()
                    .tenantId(tenantId)
                    .eventType(eventType)
                    .payload(payload)
                    .idempotencyKey(idempotencyKey)
                    .build());

            Outbox outbox = outboxRepository.save(Outbox.builder()
                    .eventId(event.getId())
                    .published(false)
                    .build());

            eventPublisher.publishEvent(new OutboxCreatedEvent(
                    outbox.getId(), event.getId(), tenantId, payload));

            final UUID eventId = event.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisTemplate.opsForValue().set(redisKey, "1", Duration.ofHours(24));
                }
            });

            log.info("Successfully ingested event: {}", eventId);
            return eventId;

        } catch (DataIntegrityViolationException dup) {
            log.warn("Duplicate event (db unique) tenant={} key={}", tenantId, idempotencyKey);
            throw new IllegalArgumentException("Duplicate event detected");
        }
    }
}