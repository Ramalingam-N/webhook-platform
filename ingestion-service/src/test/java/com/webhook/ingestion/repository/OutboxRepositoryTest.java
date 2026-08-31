package com.webhook.ingestion.repository;

import com.webhook.core.entity.Outbox;
import com.webhook.core.repository.OutboxRepository;
import com.webhook.ingestion.IngestionApplication;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = IngestionApplication.class)
class OutboxRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("R4: findUnpublishedWithLock ONLY returns unpublished rows older than 1 minute")
    void findsOnlyOldUnpublishedEvents() {
        UUID event1 = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO outbox (event_id, published, created_at) VALUES (?, false, NOW())", event1);

        UUID event2 = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO outbox (event_id, published, created_at) VALUES (?, true, NOW() - INTERVAL '2 minutes')", event2);

        UUID event3 = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO outbox (event_id, published, created_at) VALUES (?, false, NOW() - INTERVAL '2 minutes')", event3);

        List<Outbox> pending = outboxRepository.findUnpublishedWithLock(10);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getEventId()).isEqualTo(event3);
    }

    @Test
    @DisplayName("R5: markAsPublished correctly flips the boolean flag")
    void marksAsPublished() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO outbox (event_id, published, created_at) VALUES (?, false, NOW() - INTERVAL '2 minutes')", eventId);
        
        Outbox outbox = outboxRepository.findAll().get(0);

        
        int updatedRows = outboxRepository.markAsPublished(outbox.getId());
        assertThat(updatedRows).isEqualTo(1);
        
        entityManager.clear();

        Outbox updated = outboxRepository.findById(outbox.getId()).get();
        assertThat(updated.isPublished()).isTrue();
    }
}