package com.webhook.ingestion.scheduler;

import com.webhook.core.entity.Event;
import com.webhook.core.entity.Outbox;
import com.webhook.core.repository.EventRepository;
import com.webhook.core.repository.OutboxRepository;
import com.webhook.ingestion.event.OutboxCreatedEvent;
import com.webhook.ingestion.service.OutboxClaimService;
import com.webhook.ingestion.service.OutboxPublisherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxSweeperTest {

    @Mock private OutboxClaimService claimService;
    @Mock private EventRepository eventRepository;
    @Mock private OutboxPublisherService publisherService;

    @InjectMocks
    private OutboxSweeper outboxSweeper;

    @Test
    @DisplayName("S7: Sweeps unpublished rows and publishes them")
    void sweepsAndPublishes() {
        UUID eventId = UUID.randomUUID();
        Outbox outbox = Outbox.builder().id(1L).eventId(eventId).build();
        Event event = Event.builder().tenantId("tenant-1").payload("{\"data\":1}").build();

        when(claimService.claimBatch(50)).thenReturn(List.of(outbox));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        outboxSweeper.sweepUnpublishedEvents();

        ArgumentCaptor<OutboxCreatedEvent> captor = ArgumentCaptor.forClass(OutboxCreatedEvent.class);
        verify(publisherService).publish(captor.capture());

        OutboxCreatedEvent publishedEvent = captor.getValue();
        assertThat(publishedEvent.outboxId()).isEqualTo(1L);
        assertThat(publishedEvent.tenantId()).isEqualTo("tenant-1");
        assertThat(publishedEvent.payload()).isEqualTo("{\"data\":1}");
    }

    @Test
    @DisplayName("S8: Publish failure is caught; sweeper continues to next row")
    void publishFailureDoesNotCrashSweeper() {
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();

        Outbox outbox1 = Outbox.builder().id(1L).eventId(eventId1).build();
        Outbox outbox2 = Outbox.builder().id(2L).eventId(eventId2).build();
        
        Event event1 = Event.builder().tenantId("tenant-1").payload("{}").build();
        Event event2 = Event.builder().tenantId("tenant-2").payload("{}").build();

        when(claimService.claimBatch(50)).thenReturn(List.of(outbox1, outbox2));
        when(eventRepository.findById(eventId1)).thenReturn(Optional.of(event1));
        when(eventRepository.findById(eventId2)).thenReturn(Optional.of(event2));

        doThrow(new RuntimeException("Kafka down"))
                .when(publisherService).publish(argThat(e -> e.outboxId().equals(1L)));

        outboxSweeper.sweepUnpublishedEvents();

        verify(publisherService, times(2)).publish(any(OutboxCreatedEvent.class));
        verify(claimService).resetToUnpublished(1L);
        verify(publisherService).publish(argThat(e -> e.outboxId().equals(2L)));
    }

    @Test
    @DisplayName("S9: Missing Event entity is skipped gracefully")
    void missingEventIsSkipped() {
        UUID eventId = UUID.randomUUID();
        Outbox outbox = Outbox.builder().id(1L).eventId(eventId).build();

        when(claimService.claimBatch(50)).thenReturn(List.of(outbox));
        
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        outboxSweeper.sweepUnpublishedEvents();

        verify(publisherService, never()).publish(any());
    }
}