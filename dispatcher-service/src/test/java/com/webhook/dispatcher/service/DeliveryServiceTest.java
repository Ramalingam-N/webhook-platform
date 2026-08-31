package com.webhook.dispatcher.service;

import com.webhook.core.entity.Endpoint;
import com.webhook.dispatcher.security.SignatureService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
    @Mock private Mono<Void> responseMono;

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
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn((Mono) responseMono);
    }

    @Test
    @DisplayName("S10: Happy Path - Claims inbox, generates signature, sends HTTP POST")
    void happyPathAndHeaders() {
        // Arrange
        when(inboxService.tryClaim(eventId, endpoint.getId())).thenReturn(true);
        when(signatureService.sign(eq(payload), eq("whsec_123"), anyString(), eq(eventId.toString())))
                .thenReturn("sha256=abcdef");

        mockWebClientChain();
        when(responseMono.block(any(Duration.class))).thenReturn(null); // Success!

        // Act
        deliveryService.deliver(eventId, tenantId, payload, endpoint);

        // Assert
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
        // Arrange
        when(inboxService.tryClaim(eventId, endpoint.getId())).thenReturn(false);

        // Act
        deliveryService.deliver(eventId, tenantId, payload, endpoint);

        // Assert - WebClient should NEVER be touched
        verifyNoInteractions(webClient);
        verifyNoInteractions(signatureService);
    }

    @Test
    @DisplayName("S12: Network failure (500/Timeout) releases claim and flags tenant")
    void deliveryFailureReleasesClaim() {
        // Arrange
        when(inboxService.tryClaim(eventId, endpoint.getId())).thenReturn(true);
        when(signatureService.sign(any(), any(), any(), any())).thenReturn("sig");

        mockWebClientChain();
        // Simulate a timeout or 500 error from the block() method
        when(responseMono.block(any(Duration.class))).thenThrow(new RuntimeException("Timeout"));

        // Act & Assert
        assertThatThrownBy(() -> deliveryService.deliver(eventId, tenantId, payload, endpoint))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Delivery failed");

        // Assert cleanup happens correctly
        verify(inboxService).release(eventId, endpoint.getId()); // Inbox released for retry
        verify(valueOperations).set("degraded:tenant-1", "true", Duration.ofMinutes(5)); // Tenant flagged
    }

    @Test
    @DisplayName("S13: Circuit Breaker isolation - Trip after repeated failures")
    void circuitBreakerTripsAndIsolates() {
        // Arrange
        when(inboxService.tryClaim(eventId, endpoint.getId())).thenReturn(true);
        when(signatureService.sign(any(), any(), any(), any())).thenReturn("sig");

        mockWebClientChain();
        when(responseMono.block(any(Duration.class))).thenThrow(new RuntimeException("500 Internal Server Error"));

        // Act - Fail twice to trip the breaker (sliding window size is 2)
        try { deliveryService.deliver(eventId, tenantId, payload, endpoint); } catch (Exception ignored) {}
        try { deliveryService.deliver(eventId, tenantId, payload, endpoint); } catch (Exception ignored) {}

        // Reset the mock so we can verify it doesn't get called on the third attempt
        reset(webClient);

        // Act - Third attempt
        assertThatThrownBy(() -> deliveryService.deliver(eventId, tenantId, payload, endpoint))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);

        // Assert - WebClient was blocked by Circuit Breaker
        verifyNoInteractions(webClient);
        
        // Assert Inbox was still released
        verify(inboxService, times(3)).release(eventId, endpoint.getId());
    }
}