package com.example.shophub.service;

import com.example.shophub.dto.OrderResponse;
import com.example.shophub.dto.order.OrderItemResponse;
import com.example.shophub.entity.*;
import com.example.shophub.enums.OrderStatus;
import com.example.shophub.enums.ProductStatus;
import com.example.shophub.exception.ResourceNotFoundException;
import com.example.shophub.exception.SoldOutException;
import com.example.shophub.kafka.event.OrderCreatedDomainEvent;
import com.example.shophub.kafka.event.PaymentCompletedDomainEvent;
import com.example.shophub.repository.jpa.*;
import com.example.shophub.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Self-injection: Spring injects the AOP proxy of this bean back as a field.
     * Required so that internal calls to @Transactional methods (loadCartSnapshot,
     * persistOrder) go through the proxy and honour the transaction semantics.
     * @Lazy breaks the circular dependency: Spring builds OrderService first,
     * then injects the proxy lazily on first use.
     */
    @Lazy
    @Autowired
    private OrderService self;

    // ── Snapshot types (static so they can cross method boundaries) ──────────

    /** Immutable value representing one cart line at the moment of checkout. */
    private record CheckoutItem(Long productId, int qty, BigDecimal price) {}

    /** Cart state snapshot loaded before the deduction loop. */
    private record CartSnapshot(Long cartId, List<CheckoutItem> items) {}

    // =========================================================================
    // Checkout — hot path
    // =========================================================================

    /**
     * No outer @Transactional by design: each stock deduction is a committed UPDATE
     * under its own per-product lock. If persistOrder fails after deductions are already
     * committed, a @Transactional rollback cannot undo them — manual compensation does.
     * This also maximises concurrency: two users buying different products never block
     * each other at the transaction level, only at the per-product lock level.
     */
    public OrderResponse checkout() {
        Long userId = securityUtils.resolveUserId();

        CartSnapshot snapshot = self.loadCartSnapshot(userId);

        // Tracks successfully committed deductions for compensation
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
                        // Lock must be released before throwing — don't hold it during unwind
                        lock.unlock();
                        throw new SoldOutException(item.productId());
                    }

                    // Deduction committed — track it, release lock, schedule second deletion
                    deducted.add(item);
                    lock.unlock();
                    // Async: kills any stale cache entry a reader may have cached between
                    // the first deletion (step 6b) and the MySQL commit (step 6c)
                    cacheService.scheduleSecondDeletion(item.productId());

                    log.debug("[Checkout] Deducted productId={} qty={}", item.productId(), item.qty());

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                    throw new RuntimeException("Lock wait interrupted for product " + item.productId());
                } catch (RuntimeException e) {
                    // Covers SoldOutException (already unlocked above) and any other runtime failure.
                    // isHeldByCurrentThread() guard ensures we don't double-unlock.
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
                    log.info("[Checkout][Compensation] Restored productId={} qty={}",
                            item.productId(), item.qty());
                } catch (Exception compensationEx) {
                    // Compensation itself failed — stock may leak.
                    // Production fix: transactional outbox / durable compensation queue.
                    log.error("[Checkout][Compensation] FAILED to restore productId={} qty={}: {}",
                            item.productId(), item.qty(), compensationEx.getMessage());
                }
            }
            throw ex; // re-throw so controller maps to the right HTTP status
        }
    }

    /**
     * Loads cart items in a read-only transaction so lazy-loaded Product associations
     * are reachable. Validates ACTIVE status and snapshots prices.
     * Must be called via 'self' so the @Transactional proxy intercepts it.
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
            Product p = ci.getProduct(); // lazy-load safe inside @Transactional
            if (p.getStatus() != ProductStatus.ACTIVE) {
                throw new IllegalArgumentException(
                        "Product '" + p.getName() + "' (id=" + p.getId() + ") is unavailable");
            }
            checkoutItems.add(new CheckoutItem(p.getId(), ci.getQuantity(), p.getPrice()));
        }
        return new CartSnapshot(cart.getId(), checkoutItems);
    }

    /**
     * Creates Order + OrderItems + clears cart + publishes domain event in one transaction.
     * Must be called via 'self' so the @Transactional proxy intercepts it.
     * Public visibility is required for Spring's proxy to wrap it — treat as package-internal.
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
            // getReferenceById: returns a proxy (no extra SELECT); valid within this tx
            oi.setProduct(productRepository.getReferenceById(item.productId()));
            oi.setQuantity(item.qty());
            oi.setPriceAtPurchase(item.price()); // snapshot — immune to future price changes
            orderItemRepository.save(oi);
        }

        cartItemRepository.deleteByCartId(cartId);

        // Publish in-process event; @TransactionalEventListener(AFTER_COMMIT) bridge
        // forwards to Kafka only after this transaction commits (Step 9 wires the send).
        eventPublisher.publishEvent(
                new OrderCreatedDomainEvent(this, order.getId(), userId, order.getCreatedAt()));

        log.info("[Checkout] Order created: orderId={} userId={} total={} items={}",
                order.getId(), userId, total, items.size());

        // findByOrderId inside the same tx so lazy product proxies are resolvable
        return toDetailResponse(order, orderItemRepository.findByOrderId(order.getId()));
    }

    // =========================================================================
    // Pay — mock payment trigger
    // =========================================================================

    /**
     * Conditional UPDATE: exactly one of {/pay, OrderExpiryScheduler} wins.
     * rowsAffected = 0  → already PAID or CANCELLED → 409.
     * rowsAffected = 1  → PAID; publishes PaymentCompletedDomainEvent AFTER_COMMIT.
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

        // clearAutomatically = true on payIfPending ensures this re-read sees PAID + paidAt
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        eventPublisher.publishEvent(
                new PaymentCompletedDomainEvent(this, order.getId(), userId, order.getPaidAt()));

        log.info("[Pay] orderId={} userId={} paidAt={}", order.getId(), userId, order.getPaidAt());
        return toDetailResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    // =========================================================================
    // Read
    // =========================================================================

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
        // 404 (not 403) for non-owners — avoids leaking that the order exists
        if (!isAdmin() && !order.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }
        return toDetailResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    /** List-view response — items not loaded (avoids N+1 on paginated lists). */
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
