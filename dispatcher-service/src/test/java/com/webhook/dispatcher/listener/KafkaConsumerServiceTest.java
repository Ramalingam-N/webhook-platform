package com.webhook.dispatcher.listener;

import com.webhook.core.entity.DeadLetter;
import com.webhook.core.entity.Endpoint;
import com.webhook.core.repository.DeadLetterRepository;
import com.webhook.core.repository.EndpointRepository;
import com.webhook.dispatcher.service.DeliveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerServiceTest {

    @Mock private DeliveryService deliveryService;
    @Mock private DeadLetterRepository deadLetterRepository;
    @Mock private EndpointRepository endpointRepository;

    @InjectMocks
    private KafkaConsumerService consumerService;

    @Test
    @DisplayName("S14: Fan-out - 3 ACTIVE endpoints results in 3 delivery calls")
    void deliversToAllActiveEndpoints() {
        String tenantId = "tenant-1";
        byte[] eventIdBytes = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);

        when(endpointRepository.findByTenantIdAndStatus(tenantId, "ACTIVE"))
                .thenReturn(List.of(new Endpoint(), new Endpoint(), new Endpoint()));

        consumerService.consumeFastLane("payload", "webhook-fast-lane", tenantId, eventIdBytes);

        verify(deliveryService, times(3)).deliver(any(UUID.class), eq(tenantId), eq("payload"), any(Endpoint.class));
    }

    @Test
    @DisplayName("S15: Zero active endpoints results in warning, no delivery, no crash")
    void zeroEndpointsDropsEvent() {
        when(endpointRepository.findByTenantIdAndStatus("tenant-empty", "ACTIVE"))
                .thenReturn(List.of());

        consumerService.consumeFastLane("payload", "webhook-fast-lane", "tenant-empty", null);

        verify(deliveryService, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("S16: Malformed X-Event-Id header falls back to random UUID gracefully")
    void malformedEventIdFallsBack() {
        byte[] badEventId = "not-a-valid-uuid".getBytes(StandardCharsets.UTF_8);
        
        when(endpointRepository.findByTenantIdAndStatus("tenant-2", "ACTIVE"))
                .thenReturn(List.of(new Endpoint()));

        consumerService.consumeFastLane("payload", "webhook-fast-lane", "tenant-2", badEventId);

        verify(deliveryService, times(1)).deliver(any(UUID.class), eq("tenant-2"), eq("payload"), any(Endpoint.class));
    }

    @Test
    @DisplayName("S17: DLT Handler saves new DeadLetter, or increments retryCount if it already exists")
    void dltHandlerLogic() {
        UUID eventId = UUID.randomUUID();
        byte[] eventIdBytes = eventId.toString().getBytes(StandardCharsets.UTF_8);

        when(deadLetterRepository.findByEventId(eventId)).thenReturn(Optional.empty());

        consumerService.handleDlt("payload1", "retry-topic", "tenant-1", eventIdBytes);

        ArgumentCaptor<DeadLetter> captor = ArgumentCaptor.forClass(DeadLetter.class);
        verify(deadLetterRepository).save(captor.capture());

        DeadLetter savedDlq1 = captor.getValue();
        assertThat(savedDlq1.getRetryCount()).isEqualTo(0);
        assertThat(savedDlq1.getPayload()).isEqualTo("payload1");
        assertThat(savedDlq1.isReplayed()).isFalse();

        DeadLetter existingDlq = DeadLetter.builder().retryCount(0).replayed(true).build();
        when(deadLetterRepository.findByEventId(eventId)).thenReturn(Optional.of(existingDlq));

        consumerService.handleDlt("payload2", "retry-topic", "tenant-1", eventIdBytes);

        verify(deadLetterRepository, times(2)).save(captor.capture());

        DeadLetter savedDlq2 = captor.getValue();
        assertThat(savedDlq2.getRetryCount()).isEqualTo(1);
        assertThat(savedDlq2.getPayload()).isEqualTo("payload2");
        assertThat(savedDlq2.isReplayed()).isFalse();
    }
}