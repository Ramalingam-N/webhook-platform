package com.webhook.ingestion.service;

import com.webhook.core.repository.OutboxRepository;
import com.webhook.ingestion.event.OutboxCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OutboxRepository outboxRepository;

    private static final String FAST_LANE = "webhook-fast-lane";
    private static final String SLOW_LANE = "webhook-slow-lane";

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(OutboxCreatedEvent event) {
        try {
            String topic = resolveTopic(event.tenantId());
            ProducerRecord<String, String> record = buildRecord(topic, event);

            kafkaTemplate.send(record).get(3, TimeUnit.SECONDS);
            outboxRepository.markAsPublished(event.outboxId());

            log.info("Published outbox id: {} (event: {})", event.outboxId(), event.eventId());
        } catch (Exception e) {
            log.error("Failed to publish outbox id: {}. Sweeper will retry.", event.outboxId(), e);
            throw new RuntimeException("Kafka publish failed", e);
        }
    }

    private String resolveTopic(String tenantId) {
        boolean isDegraded = Boolean.TRUE.equals(redisTemplate.hasKey("degraded:" + tenantId));
        return isDegraded ? SLOW_LANE : FAST_LANE;
    }

    private ProducerRecord<String, String> buildRecord(String topic, OutboxCreatedEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.tenantId(), event.payload());
        if (event.eventId() != null) {
            record.headers().add(new RecordHeader(
                    "X-Event-Id",
                    event.eventId().toString().getBytes(StandardCharsets.UTF_8)
            ));
        }
        return record;
    }
}