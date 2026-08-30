package com.webhook.dispatcher.service;

import com.webhook.core.entity.Endpoint;
import com.webhook.dispatcher.security.SignatureService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final WebClient webClient;
    private final SignatureService signatureService;
    private final StringRedisTemplate redisTemplate;
    private final InboxService inboxService;
    private final CircuitBreakerRegistry circuitBreakerRegistry; 

    public void deliver(UUID eventId, String tenantId, String payload, Endpoint endpoint) {

        if (!inboxService.tryClaim(eventId, endpoint.getId())) {
            log.info("Idempotency hit: event {} already delivered to endpoint {} — skipping.", eventId, endpoint.getId());
            return;
        }

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = signatureService.sign(payload, endpoint.getSecret(), timestamp, eventId.toString());

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("endpoint-" + endpoint.getId());

        try {
            breaker.executeRunnable(() -> sendSigned(signature, payload, endpoint.getUrl(), timestamp));
            log.info("Delivered event {} to {}", eventId, endpoint.getUrl());
        } 
        catch (Exception ex) {
            inboxService.release(eventId, endpoint.getId());
            
            redisTemplate.opsForValue().set("degraded:" + tenantId, "true", Duration.ofMinutes(5));
            
            log.warn("Delivery failed for event {} to endpoint {}. Claim released, routing to retry.", eventId, endpoint.getId());
            throw new RuntimeException("Delivery failed for endpoint " + endpoint.getId(), ex);
        }
    }

    private void sendSigned(String signature, String payload, String url, String timestamp) {
        webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Webhook-Timestamp", timestamp)
                .header("X-Webhook-Signature", signature)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(3));
    }
}