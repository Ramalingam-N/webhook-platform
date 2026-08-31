package com.webhook.ingestion.service;

import com.webhook.core.entity.Event;
import com.webhook.core.entity.Outbox;
import com.webhook.core.repository.EventRepository;
import com.webhook.core.repository.OutboxRepository;
import com.webhook.ingestion.event.OutboxCreatedEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventIngestionServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private EventRepository eventRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EventIngestionService ingestionService;

    private final String tenantId = "tenant-1";
    private final String eventType = "user.created";
    private final String payload = "{\"user\":\"john\"}";
    private final String idempotencyKey = "idem-123";
    private final String expectedRedisKey = "idem:tenant-1:idem-123";

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }
    
    @Test
    @DisplayName("S1: Happy Path - Saves Event and Outbox, publishes internal event")
    void s1_happyPath() {
        when(redisTemplate.hasKey(expectedRedisKey)).thenReturn(Boolean.FALSE);

        UUID generatedEventId = UUID.randomUUID();
        Event savedEvent = Event.builder().id(generatedEventId).build();
        when(eventRepository.save(any(Event.class))).thenReturn(savedEvent);

        Outbox savedOutbox = Outbox.builder().id(100L).eventId(generatedEventId).build();
        when(outboxRepository.save(any(Outbox.class))).thenReturn(savedOutbox);

        UUID returnedId = ingestionService.ingestEvent(tenantId, eventType, payload, idempotencyKey);

        assertThat(returnedId).isEqualTo(generatedEventId);

        verify(eventRepository).save(any(Event.class));
        verify(outboxRepository).save(any(Outbox.class));

        ArgumentCaptor<OutboxCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OutboxCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        OutboxCreatedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.outboxId()).isEqualTo(100L);
        assertThat(publishedEvent.eventId()).isEqualTo(generatedEventId);
        assertThat(publishedEvent.tenantId()).isEqualTo(tenantId);
        assertThat(publishedEvent.payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("S2: Redis duplicate (Fast Path) - Throws immediately, no DB writes")
    void s2_redisDuplicate() {
        when(redisTemplate.hasKey(expectedRedisKey)).thenReturn(Boolean.TRUE);

        assertThatThrownBy(() -> ingestionService.ingestEvent(tenantId, eventType, payload, idempotencyKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate event detected");

        verify(eventRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("S3: DB duplicate (Redis miss/evicted) - Throws DataIntegrityViolationException")
    void s3_databaseDuplicate() {
        when(redisTemplate.hasKey(expectedRedisKey)).thenReturn(Boolean.FALSE);

        when(eventRepository.save(any(Event.class))).thenThrow(new DataIntegrityViolationException("Duplicate key"));

        assertThatThrownBy(() -> ingestionService.ingestEvent(tenantId, eventType, payload, idempotencyKey))
                .isInstanceOf(IllegalArgumentException.class);

        verify(outboxRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}