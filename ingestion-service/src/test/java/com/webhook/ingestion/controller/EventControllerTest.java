package com.webhook.ingestion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.ingestion.dto.EventRequest;
import com.webhook.ingestion.exception.GlobalExceptionHandler;
import com.webhook.ingestion.service.EventIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventController.class)
@Import(GlobalExceptionHandler.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventIngestionService ingestionService;

    private final String secretKey = "whsec_test_secret_key";

    @Test
    @DisplayName("I1: Valid POST /v1/events -> returns 202 Accepted with Event ID")
    void validPostReturns202() throws Exception {
        UUID mockId = UUID.randomUUID();
        when(ingestionService.ingestEvent("tenant-1", "user.created", "{}", "idem-123", secretKey))
                .thenReturn(mockId);

        EventRequest request = new EventRequest("tenant-1", "user.created", "{}");

        mockMvc.perform(post("/v1/events")
                        .header("Idempotency-Key", "idem-123")
                        .header("X-Tenant-Key", secretKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(mockId.toString()));
    }

    @Test
    @DisplayName("I2: Missing Idempotency-Key header -> returns 400 Bad Request")
    void missingHeaderReturns400() throws Exception {
        EventRequest request = new EventRequest("tenant-1", "user.created", "{}");

        mockMvc.perform(post("/v1/events")
                        .header("X-Tenant-Key", secretKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Required header 'Idempotency-Key' is missing"));
    }

    @Test
    @DisplayName("I3: Duplicate key throws duplicate error -> returns 409 Conflict")
    void duplicateKeyReturns409() throws Exception {
        when(ingestionService.ingestEvent(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Duplicate event detected"));

        EventRequest request = new EventRequest("tenant-1", "user.created", "{}");

        mockMvc.perform(post("/v1/events")
                        .header("Idempotency-Key", "duplicate-key")
                        .header("X-Tenant-Key", secretKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Duplicate event detected"));
    }

    @Test
    @DisplayName("I4: Missing X-Tenant-Key header -> returns 400 Bad Request")
    void missingTenantSecretReturns400() throws Exception {
        EventRequest request = new EventRequest("tenant-1", "user.created", "{}");

        mockMvc.perform(post("/v1/events")
                        .header("Idempotency-Key", "idem-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Required header 'X-Tenant-Key' is missing"));
    }
}