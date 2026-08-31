package com.webhook.ingestion.repository;

import com.webhook.core.entity.DeadLetter;
import com.webhook.core.entity.Event;
import com.webhook.core.repository.DeadLetterRepository;
import com.webhook.core.repository.EventRepository;
import com.webhook.core.repository.InboxRepository;
import com.webhook.ingestion.IngestionApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = IngestionApplication.class)
class RepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private EventRepository eventRepository;
    @Autowired private InboxRepository inboxRepository;
    @Autowired private DeadLetterRepository deadLetterRepository;

    @Test
    @DisplayName("R1: Unique constraint blocks duplicate tenant_id + idempotency_key")
    void eventUniqueConstraint() {
        Event event1 = Event.builder()
                .tenantId("tenant-1")
                .eventType("user.created")
                .payload("{}")
                .idempotencyKey("idem-123")
                .build();
        eventRepository.saveAndFlush(event1);

        Event event2 = Event.builder()
                .tenantId("tenant-1")
                .eventType("user.updated")
                .payload("{}")
                .idempotencyKey("idem-123")
                .build();

        assertThatThrownBy(() -> eventRepository.saveAndFlush(event2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("R2: Inbox claim resolves ON CONFLICT DO NOTHING correctly")
    void inboxClaimAndRelease() {
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        int claim1 = inboxRepository.claim(eventId, endpointId);
        int claim2 = inboxRepository.claim(eventId, endpointId);
        
        assertThat(claim1).isEqualTo(1);
        assertThat(claim2).isEqualTo(0);

        inboxRepository.release(eventId, endpointId);
        int claim3 = inboxRepository.claim(eventId, endpointId);
        
        assertThat(claim3).isEqualTo(1);
    }

    @Test
    @DisplayName("R3: DeadLetter markAsReplayedAtomically prevents double-clicks")
    void dlqAtomicUpdates() {
        DeadLetter dl = DeadLetter.builder()
                .eventId(UUID.randomUUID())
                .tenantId("tenant-1")
                .originalTopic("topic")
                .payload("{}")
                .build();
        dl = deadLetterRepository.saveAndFlush(dl);

        int mark1 = deadLetterRepository.markAsReplayedAtomically(dl.getId());
        assertThat(mark1).isEqualTo(1);

        int mark2 = deadLetterRepository.markAsReplayedAtomically(dl.getId());
        assertThat(mark2).isEqualTo(0);

        deadLetterRepository.resetReplayedFlag(dl.getId());
        int mark3 = deadLetterRepository.markAsReplayedAtomically(dl.getId());
        assertThat(mark3).isEqualTo(1);
    }
}