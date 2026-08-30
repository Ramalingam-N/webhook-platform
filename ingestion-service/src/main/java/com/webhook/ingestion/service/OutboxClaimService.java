package com.webhook.ingestion.service;

import com.webhook.core.entity.Outbox;
import com.webhook.core.repository.OutboxRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private final OutboxRepository outboxRepository;

    @Transactional
    public List<Outbox> claimBatch(int batchSize) {
        List<Outbox> rows = outboxRepository.findUnpublishedWithLock(batchSize);
        rows.forEach(o -> outboxRepository.markAsPublished(o.getId()));
        return rows;
    }

    @Transactional
    public void resetToUnpublished(Long outboxId) {
        outboxRepository.markAsUnpublished(outboxId);
    }
}