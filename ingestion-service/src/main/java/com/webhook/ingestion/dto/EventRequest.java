package com.webhook.ingestion.dto;

public record EventRequest(
        String tenantId,
        String eventType,
        String payload
) {}