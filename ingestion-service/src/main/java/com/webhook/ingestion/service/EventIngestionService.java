package com.webhook.ingestion.service;

import com.webhook.core.entity.Event;
import com.webhook.core.entity.Outbox;
import com.webhook.core.repository.EndpointRepository;
import com.webhook.core.repository.EventRepository;
import com.webhook.core.repository.OutboxRepository;
import com.webhook.ingestion.event.OutboxCreatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

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
    private final EndpointRepository endpointRepository;

    @Transactional
    public UUID ingestEvent(String tenantId, String eventType, String payload, String idempotencyKey, String secretKey) {
        
        validateTenantSecret(tenantId, secretKey);
        
        String redisIdemKey = "idem:" + tenantId + ":" + idempotencyKey;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(redisIdemKey))) {
            log.warn("Duplicate event blocked by Redis cache: tenant={}, key={}", tenantId, idempotencyKey);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate event detected");
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
                    redisTemplate.opsForValue().set(redisIdemKey, "true", Duration.ofHours(24));
                }
            });

            log.info("Successfully ingested event: {} for tenant: {}", eventId, tenantId);
            return eventId;

        } catch (DataIntegrityViolationException dup) {
            log.warn("Duplicate event blocked by DB unique constraint: tenant={}, key={}", tenantId, idempotencyKey);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate event detected");
        }
    }

    private void validateTenantSecret(String tenantId, String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing secret key");
        }

        String authCacheKey = "auth:" + tenantId;

        Boolean isMember = redisTemplate.opsForSet().isMember(authCacheKey, secretKey);
        if (Boolean.TRUE.equals(isMember)) {
            return;
        }

        boolean isValid = endpointRepository.existsByTenantIdAndSecretAndStatus(tenantId, secretKey, "ACTIVE");
        if (!isValid) {
            log.warn("Authentication failed for tenantId={}", tenantId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid tenant or secret key");
        }

        redisTemplate.opsForSet().add(authCacheKey, secretKey);
        redisTemplate.expire(authCacheKey, Duration.ofHours(30));
    }
}