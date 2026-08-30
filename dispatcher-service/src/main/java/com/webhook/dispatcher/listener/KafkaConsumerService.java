package com.webhook.dispatcher.listener;

import com.webhook.core.entity.DeadLetter;
import com.webhook.core.entity.Endpoint;
import com.webhook.core.repository.DeadLetterRepository;
import com.webhook.core.repository.EndpointRepository;
import com.webhook.dispatcher.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final DeliveryService deliveryService;
    private final DeadLetterRepository deadLetterRepository;
    private final EndpointRepository endpointRepository;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 5000, multiplier = 2.0),
            autoCreateTopics = "true",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlq"
    )
    @KafkaListener(topics = "webhook-fast-lane", groupId = "webhook-dispatcher-fast-group", concurrency = "4")
    public void consumeFastLane(String payload, 
                                @Header(KafkaHeaders.RECEIVED_TOPIC) String topic, 
                                @Header(KafkaHeaders.RECEIVED_KEY) String tenantId,
                                @Header(value = "X-Event-Id", required = false) byte[] eventIdBytes) {
        processEvent(payload, topic, tenantId, eventIdBytes);
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 5000, multiplier = 2.0),
            autoCreateTopics = "true",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            dltTopicSuffix = "-dlq"
    )
    @KafkaListener(topics = "webhook-slow-lane", groupId = "webhook-dispatcher-slow-group", concurrency = "1")
    public void consumeSlowLane(String payload, 
                                @Header(KafkaHeaders.RECEIVED_TOPIC) String topic, 
                                @Header(KafkaHeaders.RECEIVED_KEY) String tenantId,
                                @Header(value = "X-Event-Id", required = false) byte[] eventIdBytes) {
        processEvent(payload, topic, tenantId, eventIdBytes);
    }

    private void processEvent(String payload, String topic, String tenantId, byte[] eventIdBytes) {
        UUID eventId;
        try {
            eventId = (eventIdBytes != null) 
                    ? UUID.fromString(new String(eventIdBytes, StandardCharsets.UTF_8)) 
                    : UUID.randomUUID();
        } catch (IllegalArgumentException e) {
            log.warn("Malformed X-Event-Id header. Generating new UUID for tenant {}", tenantId);
            eventId = UUID.randomUUID();
        }
                
        log.info("Received event on [{}] for tenant [{}]", topic, tenantId);

        List<Endpoint> activeEndpoints = endpointRepository.findByTenantIdAndStatus(tenantId, "ACTIVE");

        if (activeEndpoints.isEmpty()) {
            log.warn("No active endpoints found for tenant [{}]. Dropping event [{}].", tenantId, eventId);
            return;
        }

        for (Endpoint endpoint : activeEndpoints) {
            deliveryService.deliver(eventId, tenantId, payload, endpoint);
        }
    }

    @DltHandler
    public void handleDlt(String payload,
                      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                      @Header(KafkaHeaders.RECEIVED_KEY) String tenantId,
                      @Header(value = "X-Event-Id", required = false) byte[] eventIdBytes) {
    
        UUID eventId = (eventIdBytes != null)
                ? UUID.fromString(new String(eventIdBytes, StandardCharsets.UTF_8))
                : UUID.randomUUID();

        log.error("CRITICAL: Retries exhausted for event [{}] tenant [{}]. Syncing with DLQ.", eventId, tenantId);

        DeadLetter deadLetter = deadLetterRepository.findByEventId(eventId)
                .map(existing -> {
                    existing.setReplayed(false);
                    existing.setRetryCount(existing.getRetryCount() + 1);
                    existing.setOriginalTopic(topic);
                    existing.setPayload(payload);
                    return existing;
                })
                .orElseGet(() -> DeadLetter.builder()
                        .eventId(eventId)
                        .tenantId(tenantId)
                        .originalTopic(topic)
                        .payload(payload)
                        .replayed(false)
                        .retryCount(0)
                        .build());

        deadLetterRepository.save(deadLetter);
    }
}