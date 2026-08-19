package com.webhook.dispatcher.listener;

import com.webhook.dispatcher.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final DeliveryService deliveryService;

    @KafkaListener(topics = {"webhook-fast-lane", "webhook-slow-lane"}, groupId = "webhook-dispatcher-group")
    public void consume(String payload, 
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic, 
                        @Header(KafkaHeaders.RECEIVED_KEY) String tenantId) {
                        
        log.info("Received event on [{}] for tenant [{}]", topic, tenantId);

        String dummyTargetUrl = "http://localhost:9999/mock-customer-endpoint";
        String dummySecret = "whsec_super_secret_key_for_hmac";

        try {
            deliveryService.deliver(tenantId, payload, dummyTargetUrl, dummySecret);
        } catch (Exception e) {
            log.error("Event processing halted for tenant {}. Message dropped or sent to DLQ.", tenantId);
        }
    }
}