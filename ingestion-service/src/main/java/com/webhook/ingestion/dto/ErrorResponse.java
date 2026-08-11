package com.webhook.ingestion.dto;

public record ErrorResponse(
        int status,
        String message,
        long timestamp
) {}