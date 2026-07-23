package com.example.shophub.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String ORDER_CREATED_TOPIC      = "order-created";
    public static final String PAYMENT_COMPLETED_TOPIC  = "payment-completed";
    public static final String CHECKOUT_REQUESTED_TOPIC = "checkout-requested";

    // KafkaAdmin creates any topic declared as a NewTopic bean on startup.

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentCompletedTopic() {
        return TopicBuilder.name(PAYMENT_COMPLETED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic checkoutRequestedTopic() {
        return TopicBuilder.name(CHECKOUT_REQUESTED_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
