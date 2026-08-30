package com.webhook.ingestion.scheduler;

import com.webhook.ingestion.service.OutboxClaimService;
import com.webhook.core.entity.Outbox;
import com.webhook.core.repository.EventRepository;
import com.webhook.ingestion.event.OutboxCreatedEvent;
import com.webhook.ingestion.service.OutboxPublisherService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxSweeper {

    private final OutboxClaimService claimService;
    private final EventRepository eventRepository;
    private final OutboxPublisherService publisherService;

    @Scheduled(fixedDelay = 60000)
    public void sweepUnpublishedEvents() {
        List<Outbox> claimed = claimService.claimBatch(50);

        if (claimed.isEmpty()) return;
        log.info("Sweeper claimed {} outbox rows", claimed.size());

        for (Outbox outbox : claimed) {
            var eventOpt = eventRepository.findById(outbox.getEventId());
            if (eventOpt.isEmpty()) continue;

            try {
                publisherService.publish(new OutboxCreatedEvent(
                        outbox.getId(),
                        outbox.getEventId(),
                        eventOpt.get().getTenantId(),
                        eventOpt.get().getPayload()
                ));
            } catch (Exception e) {
                claimService.resetToUnpublished(outbox.getId());
                log.error("Sweeper publish failed for outbox {}. Reset to unpublished for retry.",
                        outbox.getId(), e);
            }
        }
    }
}