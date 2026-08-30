package com.webhook.dispatcher.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic fastLaneTopic() {
        return TopicBuilder.name("webhook-fast-lane")
                .partitions(4)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic slowLaneTopic() {
        return TopicBuilder.name("webhook-slow-lane")
                .partitions(2) 
                .replicas(1)
                .build();
    }
}