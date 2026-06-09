package com.example.shophub.kafka.consumer;

import com.example.shophub.config.KafkaTopicConfig;
import com.example.shophub.dto.OrderResponse;
import com.example.shophub.dto.order.CheckoutStatusResponse;
import com.example.shophub.exception.SoldOutException;
import com.example.shophub.kafka.event.CheckoutRequestedEvent;
import com.example.shophub.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckoutRequestedConsumerTest {

    @Mock OrderService             orderService;
    @Mock ConsumerIdempotencyGuard idempotencyGuard;
    @Mock StringRedisTemplate      redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks CheckoutRequestedConsumer consumer;

    private ObjectMapper objectMapper;
    private String eventJson;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(consumer, "checkoutStatusTtlMinutes", 30);

        CheckoutRequestedEvent event = CheckoutRequestedEvent.builder()
                .checkoutId("checkout-123")
                .userId(1L)
                .requestedAt(LocalDateTime.now())
                .build();
        eventJson = objectMapper.writeValueAsString(event);
    }

    @Test
    void handleCheckoutRequested_skipsWhenAlreadyProcessed() {
        when(idempotencyGuard.isAlreadyProcessed(KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, "checkout-123"))
                .thenReturn(true);

        consumer.handleCheckoutRequested(eventJson, "checkout-123");

        verifyNoInteractions(orderService);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void handleCheckoutRequested_successPath_writesSuccessToRedis() throws Exception {
        when(idempotencyGuard.isAlreadyProcessed(any(), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setId(99L);
        when(orderService.processCheckout(1L)).thenReturn(orderResponse);

        consumer.handleCheckoutRequested(eventJson, "checkout-123");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("checkout:checkout-123"), jsonCaptor.capture(), eq(Duration.ofMinutes(30)));

        CheckoutStatusResponse saved = objectMapper.readValue(jsonCaptor.getValue(), CheckoutStatusResponse.class);
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getOrderId()).isEqualTo(99L);
        verify(idempotencyGuard).markProcessed(KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, "checkout-123");
    }

    @Test
    void handleCheckoutRequested_soldOut_writesFailedWithoutRethrowing() throws Exception {
        when(idempotencyGuard.isAlreadyProcessed(any(), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(orderService.processCheckout(1L)).thenThrow(new SoldOutException(5L));

        assertThatNoException().isThrownBy(
                () -> consumer.handleCheckoutRequested(eventJson, "checkout-123"));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("checkout:checkout-123"), jsonCaptor.capture(), eq(Duration.ofMinutes(30)));

        CheckoutStatusResponse saved = objectMapper.readValue(jsonCaptor.getValue(), CheckoutStatusResponse.class);
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getFailureReason()).contains("Sold out");
        verify(idempotencyGuard).markProcessed(KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, "checkout-123");
    }

    @Test
    void handleCheckoutRequested_transientError_rethrows() {
        when(idempotencyGuard.isAlreadyProcessed(any(), any())).thenReturn(false);
        when(orderService.processCheckout(1L)).thenThrow(new RuntimeException("DB unavailable"));

        assertThatThrownBy(() -> consumer.handleCheckoutRequested(eventJson, "checkout-123"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB unavailable");
    }

    @Test
    void handleDlt_writesFailedStatusToRedis() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        consumer.handleDlt(eventJson, KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC + ".DLT");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("checkout:checkout-123"), jsonCaptor.capture(), eq(Duration.ofMinutes(30)));

        CheckoutStatusResponse saved = objectMapper.readValue(jsonCaptor.getValue(), CheckoutStatusResponse.class);
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getFailureReason()).contains("exhausted");
    }
}
