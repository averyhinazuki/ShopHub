package com.example.shophub.service;

import com.example.shophub.dto.OrderResponse;
import com.example.shophub.dto.order.CheckoutStatusResponse;
import com.example.shophub.dto.order.OrderItemResponse;
import com.example.shophub.entity.*;
import com.example.shophub.enums.OrderStatus;
import com.example.shophub.enums.ProductStatus;
import com.example.shophub.exception.ResourceNotFoundException;
import com.example.shophub.exception.SoldOutException;
import com.example.shophub.kafka.event.CheckoutRequestedEvent;
import com.example.shophub.kafka.event.OrderCreatedDomainEvent;
import com.example.shophub.kafka.event.PaymentCompletedDomainEvent;
import com.example.shophub.kafka.producer.OrderEventProducer;
import com.example.shophub.repository.jpa.*;
import com.example.shophub.security.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository            orderRepository;
    private final OrderItemRepository        orderItemRepository;
    private final SecurityUtils              securityUtils;
    private final CartRepository             cartRepository;
    private final CartItemRepository         cartItemRepository;
    private final ProductInventoryRepository inventoryRepository;
    private final ProductRepository          productRepository;
    private final ApplicationEventPublisher  eventPublisher;
    private final RedissonClient             redissonClient;
    private final ProductCacheService        cacheService;
    private final StringRedisTemplate        redisTemplate;
    private final ObjectMapper               objectMapper;
    private final OrderEventProducer         kafkaProducer;

    @Value("${app.checkout.status-ttl-minutes:30}")
    private int checkoutStatusTtlMinutes;

    // Self-injection of the AOP proxy so internal calls to @Transactional
    // methods (loadCartSnapshot, persistOrder) honour transaction semantics.
    // @Lazy breaks the resulting self-referential construction cycle.
    @Lazy
    @Autowired
    private OrderService self;

    /** One cart line at checkout time. */
    private record CheckoutItem(Long productId, int qty, BigDecimal price) {}

    /** Cart snapshot taken before the deduction loop. */
    private record CartSnapshot(Long cartId, List<CheckoutItem> items) {}

    // ── Checkout ─────────────────────────────────────────────────────────────

    /**
     * Async checkout entry point: validates the cart, publishes a
     * CheckoutRequestedEvent, and returns a PENDING status with a checkoutId.
     * Stock deduction and order creation happen in the consumer; the client
     * polls getCheckoutStatus().
     */
    public CheckoutStatusResponse initiateCheckout() {
        Long userId = securityUtils.resolveUserId();

        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user " + userId));
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty — nothing to checkout");
        }

        String checkoutId = UUID.randomUUID().toString();
        CheckoutStatusResponse pending = CheckoutStatusResponse.builder()
                .checkoutId(checkoutId)
                .status("PENDING")
                .build();

        try {
            redisTemplate.opsForValue().set(
                    "checkout:" + checkoutId,
                    objectMapper.writeValueAsString(pending),
                    Duration.ofMinutes(checkoutStatusTtlMinutes));
        } catch (JsonProcessingException e) {
            log.error("[Checkout] Failed to serialize PENDING status for checkoutId={}", checkoutId);
        }

        kafkaProducer.sendCheckoutRequestedEvent(CheckoutRequestedEvent.builder()
                .checkoutId(checkoutId)
                .userId(userId)
                .requestedAt(LocalDateTime.now())
                .build());

        log.info("[Checkout] Accepted: checkoutId={} userId={}", checkoutId, userId);
        return pending;
    }

    /**
     * Runs the stock deduction and order creation. Called by
     * CheckoutRequestedConsumer on a Kafka listener thread, so userId is passed
     * in rather than read from the SecurityContext.
     *
     * Intentionally not wrapped in an outer @Transactional: each deduction is a
     * committed UPDATE under its own per-product lock, and the compensation loop
     * restores stock if persistOrder fails.
     */
    public OrderResponse processCheckout(Long userId) {
        CartSnapshot snapshot = self.loadCartSnapshot(userId);
        List<CheckoutItem> deducted = new ArrayList<>();

        try {
            for (CheckoutItem item : snapshot.items()) {
                RLock lock = redissonClient.getLock("lock:product:" + item.productId());
                try {
                    boolean acquired = lock.tryLock(5, 10, java.util.concurrent.TimeUnit.SECONDS);
                    if (!acquired) {
                        throw new RuntimeException(
                                "Could not acquire lock for product " + item.productId()
                                + " — try again shortly");
                    }
                    cacheService.deleteCache(item.productId());
                    int rows = inventoryRepository.deductStock(item.productId(), item.qty());
                    if (rows == 0) {
                        lock.unlock();
                        throw new SoldOutException(item.productId());
                    }
                    deducted.add(item);
                    lock.unlock();
                    cacheService.scheduleSecondDeletion(item.productId());
                    log.debug("[Checkout] Deducted productId={} qty={}", item.productId(), item.qty());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                    throw new RuntimeException("Lock wait interrupted for product " + item.productId());
                } catch (RuntimeException e) {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                    throw e;
                }
            }
            return self.persistOrder(snapshot.items(), userId, snapshot.cartId());
        } catch (Exception ex) {
            for (CheckoutItem item : deducted) {
                try {
                    cacheService.deleteCache(item.productId());
                    inventoryRepository.restoreStock(item.productId(), item.qty());
                    cacheService.scheduleSecondDeletion(item.productId());
                    log.info("[Checkout][Compensation] Restored productId={} qty={}", item.productId(), item.qty());
                } catch (Exception compensationEx) {
                    log.error("[Checkout][Compensation] FAILED to restore productId={} qty={}: {}",
                            item.productId(), item.qty(), compensationEx.getMessage());
                }
            }
            throw ex;
        }
    }

    /**
     * Returns current checkout status from Redis.
     *
     * An absent key is 404, not PENDING. Absence has at least four causes — the
     * work is genuinely queued, the id never existed (typo, stale bookmark,
     * forged value), the 30-minute TTL expired, or Redis restarted — and only the
     * first is actually pending. Reporting PENDING for all four left a correct
     * client with no terminating condition: past the TTL the key is gone, so
     * PENDING was the permanent answer and a client that stops only on a terminal
     * status polls forever.
     *
     * The TTL is not the bug — without it Redis accumulates a key per checkout
     * until it OOMs. The bug was treating absence as an ongoing state rather than
     * as absence of information.
     *
     * A key that is present but unreadable is a different case: the checkout is
     * real, we just can't read its record, so that still answers PENDING.
     */
    public CheckoutStatusResponse getCheckoutStatus(String checkoutId) {
        String json = redisTemplate.opsForValue().get("checkout:" + checkoutId);
        if (json == null) {
            throw new ResourceNotFoundException(
                    "No checkout found for id " + checkoutId
                    + " — it may have expired; check your orders");
        }
        try {
            return objectMapper.readValue(json, CheckoutStatusResponse.class);
        } catch (JsonProcessingException e) {
            log.error("[Checkout] Failed to deserialize status for checkoutId={}", checkoutId);
            return CheckoutStatusResponse.builder().checkoutId(checkoutId).status("PENDING").build();
        }
    }

    /**
     * Loads cart items in a read-only transaction so lazy Product associations
     * resolve, validates ACTIVE status, and snapshots prices. Call via 'self' so
     * the @Transactional proxy applies.
     */
    @Transactional(readOnly = true)
    public CartSnapshot loadCartSnapshot(Long userId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user " + userId));
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty — nothing to checkout");
        }
        List<CheckoutItem> checkoutItems = new ArrayList<>();
        for (CartItem ci : items) {
            Product p = ci.getProduct();
            if (p.getStatus() != ProductStatus.ACTIVE) {
                throw new IllegalArgumentException(
                        "Product '" + p.getName() + "' (id=" + p.getId() + ") is unavailable");
            }
            checkoutItems.add(new CheckoutItem(p.getId(), ci.getQuantity(), p.getPrice()));
        }
        return new CartSnapshot(cart.getId(), checkoutItems);
    }

    /**
     * Creates the Order and OrderItems, clears the cart, and publishes the domain
     * event, all in one transaction. Call via 'self' so the @Transactional proxy
     * applies; public only because the proxy requires it — treat as internal.
     */
    @Transactional
    public OrderResponse persistOrder(List<CheckoutItem> items, Long userId, Long cartId) {
        BigDecimal total = items.stream()
                .map(i -> i.price().multiply(BigDecimal.valueOf(i.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(total);
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        for (CheckoutItem item : items) {
            OrderItem oi = new OrderItem();
            oi.setOrder(order);
            // getReferenceById returns a proxy — no extra SELECT within this tx.
            oi.setProduct(productRepository.getReferenceById(item.productId()));
            oi.setQuantity(item.qty());
            oi.setPriceAtPurchase(item.price()); // price snapshot, not a live reference
            orderItemRepository.save(oi);
        }

        cartItemRepository.deleteByCartId(cartId);

        // In-process event; the AFTER_COMMIT bridge forwards it to Kafka only once
        // this transaction commits.
        eventPublisher.publishEvent(
                new OrderCreatedDomainEvent(this, order.getId(), userId, order.getCreatedAt()));

        log.info("[Checkout] Order created: orderId={} userId={} total={} items={}",
                order.getId(), userId, total, items.size());

        // Re-read inside the same tx so lazy product proxies resolve.
        return toDetailResponse(order, orderItemRepository.findByOrderId(order.getId()));
    }

    // ── Pay ──────────────────────────────────────────────────────────────────

    /**
     * Marks an order PAID via a conditional UPDATE, so exactly one of {/pay,
     * OrderExpiryScheduler} wins. rows=0 means already paid or cancelled (409);
     * rows=1 publishes PaymentCompletedDomainEvent after commit.
     */
    @Transactional
    public OrderResponse pay(Long orderId) {
        Long userId = securityUtils.resolveUserId();

        Order existing = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!isAdmin() && !existing.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = orderRepository.payIfPending(orderId, now);
        if (rows == 0) {
            throw new IllegalStateException(
                    "Order " + orderId
                    + " cannot be paid (status is not PENDING — already paid or cancelled)");
        }

        // payIfPending clears the persistence context, so this re-read sees PAID + paidAt.
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        eventPublisher.publishEvent(
                new PaymentCompletedDomainEvent(this, order.getId(), userId, order.getPaidAt()));

        log.info("[Pay] orderId={} userId={} paidAt={}", order.getId(), userId, order.getPaidAt());
        return toDetailResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public Page<OrderResponse> getMyOrders(Pageable pageable) {
        Long userId = securityUtils.resolveUserId();
        return orderRepository.findByUserId(userId, pageable).map(this::toListResponse);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponse> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable).map(this::toListResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Long userId = securityUtils.resolveUserId();
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        // 404, not 403, for non-owners so order existence isn't leaked.
        if (!isAdmin() && !order.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }
        return toDetailResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    /** List-view response without items — avoids N+1 on paginated lists. */
    private OrderResponse toListResponse(Order order) {
        OrderResponse res = new OrderResponse();
        res.setId(order.getId());
        res.setUserId(order.getUserId());
        res.setStatus(order.getStatus());
        res.setTotalAmount(order.getTotalAmount());
        res.setCreatedAt(order.getCreatedAt());
        res.setPaidAt(order.getPaidAt());
        return res;
    }

    /** Detail-view response — includes order items with productName + lineTotal. */
    private OrderResponse toDetailResponse(Order order, List<OrderItem> items) {
        OrderResponse res = toListResponse(order);
        res.setItems(items.stream().map(this::toItemResponse).toList());
        return res;
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        OrderItemResponse r = new OrderItemResponse();
        r.setId(item.getId());
        r.setProductId(item.getProduct().getId());
        r.setProductName(item.getProduct().getName());
        r.setQuantity(item.getQuantity());
        r.setPriceAtPurchase(item.getPriceAtPurchase());
        r.setLineTotal(item.getPriceAtPurchase().multiply(BigDecimal.valueOf(item.getQuantity())));
        return r;
    }
}
