package com.webhook.ingestion.controller;

import com.webhook.ingestion.dto.EventRequest;
import com.webhook.ingestion.service.EventIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventIngestionService ingestionService;

    @PostMapping
    public ResponseEntity<Map<String, UUID>> ingest(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Tenant-Key") String secretKey,
            @RequestBody EventRequest request) {

        UUID eventId = ingestionService.ingestEvent(
                request.tenantId(),
                request.eventType(),
                request.payload(),
                idempotencyKey,
                secretKey
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("eventId", eventId));
    }
}