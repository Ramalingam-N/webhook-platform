package com.webhook.ingestion.service;

import com.webhook.core.entity.Event;
import com.webhook.core.entity.Outbox;
import com.webhook.core.repository.EventRepository;
import com.webhook.core.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventIngestionService {

    private final StringRedisTemplate redisTemplate;
    private final EventRepository eventRepository;
    private final OutboxRepository outboxRepository;

    @Transactional
    public UUID ingestEvent(String tenantId, String eventType, String payload, String idempotencyKey) {
        String redisKey = "idem:" + tenantId + ":" + idempotencyKey;

        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", Duration.ofHours(24));

        if (Boolean.FALSE.equals(isNew)) {
            log.warn("Duplicate event detected for tenant: {} with key: {}", tenantId, idempotencyKey);
            throw new IllegalArgumentException("Duplicate event detected");
        }

        // 2. Create Event
        Event event = Event.builder()
                .tenantId(tenantId)
                .eventType(eventType)
                .payload(payload)
                .idempotencyKey(idempotencyKey)
                .build();

        // 3. Save Event and Outbox in the exact same DB Transaction
        eventRepository.save(event);

        Outbox outbox = Outbox.builder()
                .eventId(event.getId())
                .published(false)
                .build();
        outboxRepository.save(outbox);

        log.info("Successfully ingested event: {}", event.getId());
        return event.getId();
    }
}