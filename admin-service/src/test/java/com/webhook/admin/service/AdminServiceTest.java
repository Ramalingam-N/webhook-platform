package com.webhook.admin.service;

import com.webhook.admin.config.SsrfValidator;
import com.webhook.core.entity.DeadLetter;
import com.webhook.core.entity.Endpoint;
import com.webhook.core.repository.DeadLetterRepository;
import com.webhook.core.repository.EndpointRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private EndpointRepository endpointRepository;
    @Mock private DeadLetterRepository deadLetterRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;
    @Mock private SsrfValidator ssrfValidator;
    @Mock private DlqReplayMarker replayMarker;

    @InjectMocks
    private AdminService adminService;

    private DeadLetter deadLetter;
    private final UUID dlqId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deadLetter = DeadLetter.builder()
                .id(dlqId)
                .eventId(eventId)
                .tenantId("tenant-1")
                .payload("{\"status\":\"failed\"}")
                .build();
    }

    @Test
    @DisplayName("S18: Valid replay -> Claims row, sends to Kafka with original Event ID")
    void replayHappyPath() throws Exception {
        when(deadLetterRepository.findById(dlqId)).thenReturn(Optional.of(deadLetter));
        when(replayMarker.claim(dlqId)).thenReturn(1);

        SendResult<String, String> mockSendResult = mock(SendResult.class);
        RecordMetadata mockMetadata = mock(RecordMetadata.class);
        when(mockSendResult.getRecordMetadata()).thenReturn(mockMetadata);
        when(mockMetadata.partition()).thenReturn(0);
        when(mockMetadata.offset()).thenReturn(100L);

        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.complete(mockSendResult);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        adminService.replayDeadLetter(dlqId);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("webhook-fast-lane");
        assertThat(record.key()).isEqualTo("tenant-1");
        
        Header eventIdHeader = record.headers().lastHeader("X-Event-Id");
        assertThat(new String(eventIdHeader.value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
    }

    @Test
    @DisplayName("S19: Already replayed -> Throws 409 Conflict, no Kafka send")
    void alreadyReplayedThrows() {
        when(deadLetterRepository.findById(dlqId)).thenReturn(Optional.of(deadLetter));
        when(replayMarker.claim(dlqId)).thenReturn(0);

            assertThatThrownBy(() -> adminService.replayDeadLetter(dlqId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("This record has already been replayed or is currently in-flight");

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("S20: RESET FIX - Kafka timeout rolls back the claim so it's not stuck")
    void resetFixOnKafkaFailure() throws Exception {
        when(deadLetterRepository.findById(dlqId)).thenReturn(Optional.of(deadLetter));
        when(replayMarker.claim(dlqId)).thenReturn(1);

        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new TimeoutException("Kafka broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        assertThatThrownBy(() -> adminService.replayDeadLetter(dlqId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to send message");

        verify(replayMarker).reset(dlqId);
    }

    @Test
    @DisplayName("S21: Unknown DLQ ID -> Throws IllegalArgumentException")
    void unknownId() {
        when(deadLetterRepository.findById(dlqId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.replayDeadLetter(dlqId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DLQ record not found");
    }

    @Test
    @DisplayName("S22: Register Endpoint calls SSRF validator and generates secret")
    void registerEndpoint() {
        Endpoint mockEndpoint = new Endpoint();
        when(endpointRepository.save(any(Endpoint.class))).thenReturn(mockEndpoint);

        Endpoint result = adminService.registerEndpoint("tenant-1", "https://api.customer.com");

        verify(ssrfValidator).validateUrl("https://api.customer.com");
        
        ArgumentCaptor<Endpoint> captor = ArgumentCaptor.forClass(Endpoint.class);
        verify(endpointRepository).save(captor.capture());
        
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-1");
        assertThat(captor.getValue().getSecret()).startsWith("whsk_");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(result).isNotNull();
    }
}