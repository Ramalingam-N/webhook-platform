package com.webhook.dispatcher.service;

import com.webhook.dispatcher.security.SignatureService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final WebClient webClient;
    private final SignatureService signatureService;
    private final StringRedisTemplate redisTemplate;

    @CircuitBreaker(name = "endpoint-breaker", fallbackMethod = "fallbackDelivery")
    public void deliver(String tenantId, String payload, String targetUrl, String secret) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = signatureService.sign(payload, secret, timestamp);

        log.info("Attempting delivery for tenant {} to URL {}", tenantId, targetUrl);

        webClient.post()
                .uri(targetUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Webhook-Timestamp", timestamp)
                .header("X-Webhook-Signature", signature)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(3));

        log.info("Successfully delivered payload for tenant {}", tenantId);
        
        redisTemplate.delete("degraded:" + tenantId);
    }

    public void fallbackDelivery(String tenantId, String payload, String targetUrl, String secret, Throwable t) {
        log.warn("Delivery failed for tenant {}. Circuit Breaker triggered. Reason: {}", tenantId, t.getMessage());
        
        redisTemplate.opsForValue().set("degraded:" + tenantId, "true", Duration.ofMinutes(5));
        
        log.info("Tenant {} flagged as degraded. Future events routed to slow lane.", tenantId);
        
        throw new RuntimeException("Delivery failed, routing to retry queue.");
    }
}