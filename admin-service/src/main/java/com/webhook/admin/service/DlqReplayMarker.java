package com.webhook.admin.service;

import com.webhook.core.repository.DeadLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DlqReplayMarker {

    private final DeadLetterRepository deadLetterRepository;

    @Transactional
    public int claim(UUID dlqId) {
        return deadLetterRepository.markAsReplayedAtomically(dlqId);
    }

    @Transactional
    public void reset(UUID dlqId) {
        deadLetterRepository.resetReplayedFlag(dlqId);
    }
}