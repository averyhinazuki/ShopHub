package com.example.shophub.kafka.consumer;

import com.example.shophub.config.KafkaTopicConfig;
import com.example.shophub.dto.OrderResponse;
import com.example.shophub.dto.order.CheckoutStatusResponse;
import com.example.shophub.exception.SoldOutException;
import com.example.shophub.kafka.event.CheckoutRequestedEvent;
import com.example.shophub.metrics.DomainMetrics;
import com.example.shophub.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
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

    // A real registry, not a mock: Counter.builder(...).register(mock) returns null.
    // It also lets the tests assert the counts rather than just the interactions.
    private MeterRegistry registry;
    private ObjectMapper objectMapper;
    private String eventJson;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(consumer, "metrics", new DomainMetrics(registry));
        ReflectionTestUtils.setField(consumer, "checkoutStatusTtlMinutes", 30);

        CheckoutRequestedEvent event = CheckoutRequestedEvent.builder()
                .checkoutId("checkout-123")
                .userId(1L)
                .requestedAt(LocalDateTime.now())
                .build();
        eventJson = objectMapper.writeValueAsString(event);
    }

    // ── handleCheckoutRequested ───────────────────────────────────────────────

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
    void handleCheckoutRequested_markProcessedFails_doesNotRethrow() throws Exception {
        // Simulates the edge case: processing succeeds, status written, but dedup Redis write fails.
        // Handler must NOT rethrow — offset should commit so @RetryableTopic doesn't retry a completed checkout.
        when(idempotencyGuard.isAlreadyProcessed(any(), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setId(99L);
        when(orderService.processCheckout(1L)).thenReturn(orderResponse);
        doThrow(new RuntimeException("Redis blip")).when(idempotencyGuard).markProcessed(any(), any());

        assertThatNoException().isThrownBy(
                () -> consumer.handleCheckoutRequested(eventJson, "checkout-123"));

        verify(valueOps).set(eq("checkout:checkout-123"), anyString(), any(Duration.class));
    }

    /**
     * F1. By the time writeStatus runs the order is fully committed — row written,
     * items written, cart cleared, stock deducted. A Redis failure there must not
     * fail the message.
     *
     * It used to: writeStatus caught only JsonProcessingException, so a
     * RedisConnectionFailureException propagated out of the handler, @RetryableTopic
     * redelivered, loadCartSnapshot found the cart already empty and threw
     * IllegalArgumentException, retries exhausted, and @DltHandler read a status
     * still sitting at PENDING so its SUCCESS-guard didn't trip — and wrote FAILED.
     * The customer was told their checkout failed while a real, payable order existed.
     */
    @Test
    void handleCheckoutRequested_statusWriteFailsAfterOrderCommitted_doesNotRethrow() throws Exception {
        when(idempotencyGuard.isAlreadyProcessed(any(), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setId(99L);
        when(orderService.processCheckout(1L)).thenReturn(orderResponse);
        doThrow(new RedisConnectionFailureException("Redis unreachable"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatNoException().isThrownBy(
                () -> consumer.handleCheckoutRequested(eventJson, "checkout-123"));

        // Must still mark processed: a redelivery would hit an empty cart and fail spuriously.
        verify(idempotencyGuard).markProcessed(KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, "checkout-123");
    }

    /**
     * F22. Without these counters every defect in this file would be invisible in
     * production while the dashboard stayed green.
     */
    @Test
    void counters_recordOutcomesTheDashboardCanSee() throws Exception {
        when(idempotencyGuard.isAlreadyProcessed(any(), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setId(99L);
        when(orderService.processCheckout(1L)).thenReturn(orderResponse);

        consumer.handleCheckoutRequested(eventJson, "checkout-123");

        assertThat(registry.get("shophub.checkout").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("shophub.checkout.dlt").counter().count()).isZero();

        // Anything reaching the DLT exhausted retries and nobody has looked at it.
        when(valueOps.get("checkout:checkout-123")).thenReturn(null);
        consumer.handleDlt(eventJson, KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC + ".DLT");

        assertThat(registry.get("shophub.checkout.dlt").counter().count()).isEqualTo(1.0);
    }

    /** The F1 path is counted too — an order exists but its status was lost. */
    @Test
    void counters_recordAStatusWriteThatCouldNotBePersisted() throws Exception {
        when(idempotencyGuard.isAlreadyProcessed(any(), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setId(99L);
        when(orderService.processCheckout(1L)).thenReturn(orderResponse);
        doThrow(new RedisConnectionFailureException("Redis unreachable"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        consumer.handleCheckoutRequested(eventJson, "checkout-123");

        assertThat(registry.get("shophub.checkout.status.write.failed").counter().count())
                .isEqualTo(1.0);
    }

    /**
     * F1, DLT side. If the status read fails we cannot rule out a SUCCESS sitting
     * there unread, so the handler must not fall through and write FAILED over it.
     */
    @Test
    void handleDlt_statusReadFails_doesNotWriteFailed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("checkout:checkout-123"))
                .thenThrow(new RedisConnectionFailureException("Redis unreachable"));

        assertThatNoException().isThrownBy(
                () -> consumer.handleDlt(eventJson, KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC + ".DLT"));

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
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

    // ── handleDlt ─────────────────────────────────────────────────────────────

    @Test
    void handleDlt_writesFailedWhenNoExistingStatus() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("checkout:checkout-123")).thenReturn(null);

        consumer.handleDlt(eventJson, KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC + ".DLT");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("checkout:checkout-123"), jsonCaptor.capture(), eq(Duration.ofMinutes(30)));

        CheckoutStatusResponse saved = objectMapper.readValue(jsonCaptor.getValue(), CheckoutStatusResponse.class);
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getFailureReason()).contains("exhausted");
    }

    @Test
    void handleDlt_doesNotOverwriteSuccessStatus() throws Exception {
        // Guards the edge case: dedup Redis write failed after a successful checkout,
        // causing a retry chain that reaches DLT. Must NOT overwrite the SUCCESS status.
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String successJson = "{\"checkoutId\":\"checkout-123\",\"status\":\"SUCCESS\",\"orderId\":99,\"failureReason\":null}";
        when(valueOps.get("checkout:checkout-123")).thenReturn(successJson);

        consumer.handleDlt(eventJson, KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC + ".DLT");

        verify(valueOps, never()).set(eq("checkout:checkout-123"), anyString(), any(Duration.class));
    }
}
