package com.webhook.ingestion.listener;

import com.webhook.core.repository.OutboxRepository;
import com.webhook.ingestion.event.OutboxCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OutboxRepository outboxRepository;

    private static final String FAST_LANE_TOPIC = "webhook-fast-lane";
    private static final String SLOW_LANE_TOPIC = "webhook-slow-lane";

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleOutboxEvent(OutboxCreatedEvent event) {
        try {
            String degradationKey = "degraded:" + event.tenantId();
            boolean isDegraded = Boolean.TRUE.equals(redisTemplate.hasKey(degradationKey));
            String targetTopic = isDegraded ? SLOW_LANE_TOPIC : FAST_LANE_TOPIC;

            kafkaTemplate.send(targetTopic, event.tenantId(), event.payload());
            
            outboxRepository.findById(event.outboxId()).ifPresent(outbox -> {
                outbox.setPublished(true);
                outboxRepository.save(outbox);
            });
            
            log.info("Instant Dual-Path publish success for event: {}", event.eventId());
            
        } catch (Exception e) {
            log.error("Instant publish failed for event {}. Sweeper will retry.", event.eventId(), e);
        }
    }
}