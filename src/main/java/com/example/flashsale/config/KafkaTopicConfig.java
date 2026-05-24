package com.example.flashsale.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String ORDER_CREATED_TOPIC     = "order-created";
    public static final String PAYMENT_COMPLETED_TOPIC = "payment-completed";

    /**
     * Declares the order-created topic.
     * Spring Boot's KafkaAdmin will create it automatically on startup
     * if it doesn't already exist in the broker.
     */
    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Declares the payment-completed topic.
     */
    @Bean
    public NewTopic paymentCompletedTopic() {
        return TopicBuilder.name(PAYMENT_COMPLETED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
