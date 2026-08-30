package com.webhook.admin.service;

import com.webhook.admin.config.SsrfValidator;
import com.webhook.core.entity.DeadLetter;
import com.webhook.core.entity.Endpoint;
import com.webhook.core.repository.DeadLetterRepository;
import com.webhook.core.repository.EndpointRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final EndpointRepository endpointRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SsrfValidator ssrfValidator;
    private final DlqReplayMarker replayMarker;

    @Transactional
    public Endpoint registerEndpoint(String tenantId, String url) {
        ssrfValidator.validateUrl(url);
        String secret = "whsk_" + java.util.UUID.randomUUID().toString().replace("-", "");
        Endpoint endpoint = Endpoint.builder()
                .tenantId(tenantId)
                .url(url)
                .secret(secret)
                .status("ACTIVE")
                .build();
        return endpointRepository.save(endpoint);
    }

    public List<DeadLetter> getDeadLetters() {
        return deadLetterRepository.findAll();
    }

    public void replayDeadLetter(UUID dlqId) {
        DeadLetter dl = deadLetterRepository.findById(dlqId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ record not found"));

        if (replayMarker.claim(dlqId) == 0) {
            throw new IllegalStateException("This record has already been replayed or is currently in-flight.");
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(
                "webhook-fast-lane", dl.getTenantId(), dl.getPayload());

        record.headers().add(new RecordHeader(
                "X-Event-Id", dl.getEventId().toString().getBytes(StandardCharsets.UTF_8)));

        try {
            SendResult<String, String> result = kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
            
            log.info("Replayed DLQ {} to partition {} with offset {}", 
                    dlqId, 
                    result.getRecordMetadata().partition(), 
                    result.getRecordMetadata().offset());
        } catch (Exception ex) {
            replayMarker.reset(dlqId);
            log.error("Replay failed for DLQ {}; reset claim", dlqId, ex);
            throw new RuntimeException("Failed to send message to Kafka topic", ex);
        }
    }
}