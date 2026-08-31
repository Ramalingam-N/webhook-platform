package com.webhook.ingestion.service;

import com.webhook.core.repository.OutboxRepository;
import com.webhook.ingestion.event.OutboxCreatedEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private OutboxRepository outboxRepository;

    @InjectMocks
    private OutboxPublisherService publisherService;

    private OutboxCreatedEvent event;
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        event = new OutboxCreatedEvent(100L, eventId, "tenant-1", "{\"data\":\"test\"}");
    }

    @Test
    @DisplayName("S4: Happy path - Fast lane routing, successful publish, headers attached")
    void happyPathFastLane() throws Exception {
        when(redisTemplate.hasKey("degraded:tenant-1")).thenReturn(false);

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(mock(SendResult.class));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        publisherService.publish(event);

        verify(outboxRepository).markAsPublished(100L);

        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        
        ProducerRecord<String, String> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo("webhook-fast-lane");
        assertThat(record.key()).isEqualTo("tenant-1");
        assertThat(record.value()).isEqualTo("{\"data\":\"test\"}");

        Header eventIdHeader = record.headers().lastHeader("X-Event-Id");
        assertThat(eventIdHeader).isNotNull();
        assertThat(new String(eventIdHeader.value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
    }

    @Test
    @DisplayName("S5: Degraded tenant routes to Slow lane")
    void slowLaneRouting() {
        when(redisTemplate.hasKey("degraded:tenant-1")).thenReturn(true);

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(mock(SendResult.class));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        publisherService.publish(event);

        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        assertThat(recordCaptor.getValue().topic()).isEqualTo("webhook-slow-lane");
    }

    @Test
    @DisplayName("S6: Kafka timeout blocks DB update (Crash-proof guarantee)")
    void kafkaTimeoutBlocksDbUpdate() throws Exception {
        when(redisTemplate.hasKey("degraded:tenant-1")).thenReturn(false);

        CompletableFuture<SendResult<String, String>> slowFuture = mock(CompletableFuture.class);
        when(slowFuture.get(3, TimeUnit.SECONDS)).thenThrow(new TimeoutException("Kafka is down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(slowFuture);

        assertThatThrownBy(() -> publisherService.publish(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Kafka publish failed");

        verify(outboxRepository, never()).markAsPublished(anyLong());
    }
}