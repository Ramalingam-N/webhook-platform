package com.webhook.ingestion.event;

import java.util.UUID;

public record OutboxCreatedEvent(
    Long outboxId,
    UUID eventId,
    String tenantId,
    String payload
) {}