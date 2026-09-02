package com.webhook.dispatcher.service;

import com.webhook.core.entity.Endpoint;
import com.webhook.dispatcher.security.SignatureService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock private WebClient webClient;
    @Mock private SignatureService signatureService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private InboxService inboxService;

    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;
    @Mock private ResponseEntity<Void> responseEntity;
    @Mock private Mono<ResponseEntity<Void>> responseMono;

    private CircuitBreakerRegistry registry;
    private DeliveryService deliveryService;

    private final UUID eventId = UUID.randomUUID();
    private final String tenantId = "tenant-1";
    private final String payload = "{\"status\":\"ok\"}";
    private Endpoint endpoint;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .failureRateThreshold(50)
                .build();
        registry = CircuitBreakerRegistry.of(config);

        deliveryService = new DeliveryService(webClient, signatureService, redisTemplate, inboxService, registry);

        endpoint = Endpoint.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .url("https://api.customer.com/hook")
                .secret("whsk_123")
                .build();
                
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private void mockWebClientChain() {
        doReturn(requestBodyUriSpec).when(webClient).post();
        doReturn(requestBodySpec).when(requestBodyUriSpec).uri(anyString());
        doReturn(requestBodySpec).when(requestBodySpec).contentType(any());
        doReturn(requestBodySpec).when(requestBodySpec).header(anyString(), anyString());
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
        doReturn(responseSpec).when(requestHeadersSpec).retrieve();
        doReturn(responseMono).when(responseSpec).toBodilessEntity();
    }

    @Test
    @DisplayName("S10: Happy Path - Claims inbox, generates signature, sends HTTP POST")
    void happyPathAndHeaders() {
        when(inboxService.tryClaim(eventId, endpoint.getId())).thenReturn(true);
        when(signatureService.sign(eq(payload), eq("whsk_123"), anyString(), eq(eventId.toString())))
                .thenReturn("sha256=abcdef");

        mockWebClientChain();
        when(responseMono.block(any(Duration.class))).thenReturn(null);

        deliveryService.deliver(eventId, tenantId, payload, endpoint);

        verify(inboxService).tryClaim(eventId, endpoint.getId());
        verify(requestBodyUriSpec).uri("https://api.customer.com/hook");
        verify(requestBodySpec).header("X-Webhook-Signature", "sha256=abcdef");
        verify(requestBodySpec).header(eq("X-Webhook-Timestamp"), anyString());
        verify(requestBodySpec).bodyValue(payload);
        verify(responseMono).block(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("S11: Idempotency Hit - If claim fails, abort delivery immediately")
    void idempotencyHitAborts() {
        when(inboxService.tryClaim(eventId, endpoint.getId())).thenReturn(false);

        deliveryService.deliver(eventId, tenantId, payload, endpoint);

        verifyNoInteractions(webClient);
        verifyNoInteractions(signatureService);
    }

    @Test
    @DisplayName("S12: Network failure (500/Timeout) releases claim and flags tenant")
    void deliveryFailureReleasesClaim() {
        when(inboxService.tryClaim(eventId, endpoint.getId())).thenReturn(true);
        when(signatureService.sign(any(), any(), any(), any())).thenReturn("sig");

        mockWebClientChain();
        when(responseMono.block(any(Duration.class))).thenThrow(new RuntimeException("Timeout"));

        assertThatThrownBy(() -> deliveryService.deliver(eventId, tenantId, payload, endpoint))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Delivery failed");

        verify(inboxService).release(eventId, endpoint.getId());
        verify(valueOperations).set("degraded:tenant-1", "true", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("S13: Circuit Breaker isolation - Trip after repeated failures")
    void circuitBreakerTripsAndIsolates() {
        when(inboxService.tryClaim(eventId, endpoint.getId())).thenReturn(true);
        when(signatureService.sign(any(), any(), any(), any())).thenReturn("sig");

        mockWebClientChain();
        when(responseMono.block(any(Duration.class))).thenThrow(new RuntimeException("500 Internal Server Error"));

        try { deliveryService.deliver(eventId, tenantId, payload, endpoint); } catch (Exception ignored) {}
        try { deliveryService.deliver(eventId, tenantId, payload, endpoint); } catch (Exception ignored) {}

        reset(webClient);

        assertThatThrownBy(() -> deliveryService.deliver(eventId, tenantId, payload, endpoint))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);

        verifyNoInteractions(webClient);
        
        verify(inboxService, times(3)).release(eventId, endpoint.getId());
    }
}