package com.example.shophub.kafka.consumer;

import com.example.shophub.config.KafkaTopicConfig;
import com.example.shophub.document.OrderActivityLog;
import com.example.shophub.kafka.event.OrderCreatedEvent;
import com.example.shophub.kafka.event.PaymentCompletedEvent;
import com.example.shophub.repository.mongo.OrderActivityLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock OrderActivityLogRepository activityLogRepository;
    @Mock ConsumerIdempotencyGuard   idempotencyGuard;
    @InjectMocks OrderEventConsumer  consumer;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
    }

    @Test
    void handleOrderCreated_skipsWhenAlreadyProcessed() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(42L, 1L, LocalDateTime.now());
        String message = objectMapper.writeValueAsString(event);

        when(idempotencyGuard.isAlreadyProcessed(KafkaTopicConfig.ORDER_CREATED_TOPIC, "42"))
                .thenReturn(true);

        consumer.handleOrderCreated(message, "42");

        verifyNoInteractions(activityLogRepository);
    }

    @Test
    void handleOrderCreated_newMessage_savesActivityLogAndMarksProcessed() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(42L, 1L, LocalDateTime.now());
        String message = objectMapper.writeValueAsString(event);

        when(idempotencyGuard.isAlreadyProcessed(KafkaTopicConfig.ORDER_CREATED_TOPIC, "42"))
                .thenReturn(false);

        consumer.handleOrderCreated(message, "42");

        ArgumentCaptor<OrderActivityLog> captor = ArgumentCaptor.forClass(OrderActivityLog.class);
        verify(activityLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEvent()).isEqualTo("ORDER_CREATED");
        assertThat(captor.getValue().getOrderId()).isEqualTo(42L);
        verify(idempotencyGuard).markProcessed(KafkaTopicConfig.ORDER_CREATED_TOPIC, "42");
    }

    @Test
    void handleOrderCreated_propagatesExceptionForRetry() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(42L, 1L, LocalDateTime.now());
        String message = objectMapper.writeValueAsString(event);

        when(idempotencyGuard.isAlreadyProcessed(any(), any())).thenReturn(false);
        when(activityLogRepository.save(any())).thenThrow(new RuntimeException("Mongo down"));

        assertThatThrownBy(() -> consumer.handleOrderCreated(message, "42"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Mongo down");
    }

    // ── handlePaymentCompleted ────────────────────────────────────────────────

    @Test
    void handlePaymentCompleted_skipsWhenAlreadyProcessed() throws Exception {
        PaymentCompletedEvent event = new PaymentCompletedEvent(42L, 1L, LocalDateTime.now());
        String message = objectMapper.writeValueAsString(event);

        when(idempotencyGuard.isAlreadyProcessed(KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, "42"))
                .thenReturn(true);

        consumer.handlePaymentCompleted(message, "42");

        verifyNoInteractions(activityLogRepository);
    }

    @Test
    void handlePaymentCompleted_newMessage_savesActivityLogAndMarksProcessed() throws Exception {
        PaymentCompletedEvent event = new PaymentCompletedEvent(42L, 1L, LocalDateTime.now());
        String message = objectMapper.writeValueAsString(event);

        when(idempotencyGuard.isAlreadyProcessed(KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, "42"))
                .thenReturn(false);

        consumer.handlePaymentCompleted(message, "42");

        ArgumentCaptor<OrderActivityLog> captor = ArgumentCaptor.forClass(OrderActivityLog.class);
        verify(activityLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEvent()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(captor.getValue().getOrderId()).isEqualTo(42L);
        verify(idempotencyGuard).markProcessed(KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, "42");
    }

    // ── handleDlt ─────────────────────────────────────────────────────────────

    @Test
    void handleDlt_doesNotInteractWithMongo() {
        consumer.handleDlt("some-message", KafkaTopicConfig.ORDER_CREATED_TOPIC + ".dlt");
        verifyNoInteractions(activityLogRepository);
    }
}
