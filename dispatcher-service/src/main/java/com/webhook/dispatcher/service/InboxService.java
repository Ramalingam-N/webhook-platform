package com.webhook.dispatcher.service;

import com.webhook.core.repository.InboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InboxService {

    private final InboxRepository inboxRepository;

    @Transactional
    public boolean tryClaim(UUID eventId, UUID endpointId) {
        return inboxRepository.claim(eventId, endpointId) == 1;
    }

    @Transactional
    public void release(UUID eventId, UUID endpointId) {
        inboxRepository.release(eventId, endpointId);
    }
}