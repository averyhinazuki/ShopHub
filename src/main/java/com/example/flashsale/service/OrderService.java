package com.example.flashsale.service;

import com.example.flashsale.dto.OrderResponse;
import com.example.flashsale.dto.order.OrderItemResponse;
import com.example.flashsale.entity.*;
import com.example.flashsale.enums.OrderStatus;
import com.example.flashsale.enums.ProductStatus;
import com.example.flashsale.exception.ResourceNotFoundException;
import com.example.flashsale.exception.SoldOutException;
import com.example.flashsale.kafka.event.OrderCreatedDomainEvent;
import com.example.flashsale.kafka.event.PaymentCompletedDomainEvent;
import com.example.flashsale.repository.jpa.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 7  — checkout / pay / read; sequential deduction, NO Redisson lock, NO cache invalidation.
 *           Demonstrates the race condition that Step 8 fixes.
 * Step 8  — add Redisson lock + cache-aside + delayed double deletion to checkout.
 * Step 9  — OrderEventKafkaBridge forwards domain events to Kafka topics AFTER_COMMIT.
 * Step 11 — OrderExpiryScheduler + GET /api/orders [ADMIN].
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository             orderRepository;
    private final OrderItemRepository         orderItemRepository;
    private final UserRepository              userRepository;
    private final CartRepository              cartRepository;
    private final CartItemRepository          cartItemRepository;
    private final ProductInventoryRepository  inventoryRepository;
    private final ApplicationEventPublisher   eventPublisher;

    // -------------------------------------------------------------------------
    // Checkout — hot path
    // -------------------------------------------------------------------------

    /**
     * Step 7: sequential deduction with MySQL conditional UPDATE.
     * No Redisson lock (added Step 8). No cache invalidation (added Step 8).
     *
     * Transaction wraps the full checkout so that if order/cart writes fail after
     * all deductions succeed, the deductions are rolled back automatically.
     * Step 8 will restructure: each deduction commits under its own per-product lock,
     * and the compensation runs manually (deductions are already committed by then).
     */
    @Transactional
    public OrderResponse checkout() {
        Long userId = resolveUserId();

        // ── Step 2: Load cart + snapshot product info ─────────────────────────
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user " + userId));

        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty — nothing to checkout");
        }

        // Snapshot: { productId, quantity, price, productRef }
        // Validate ACTIVE status BEFORE any deduction (no rollback needed yet).
        record Snapshot(Long productId, int qty, BigDecimal price, Product product) {}
        List<Snapshot> snapshots = new ArrayList<>();
        for (CartItem ci : cartItems) {
            Product p = ci.getProduct();
            if (p.getStatus() != ProductStatus.ACTIVE) {
                throw new IllegalArgumentException("Product '" + p.getName() + "' (id=" + p.getId() + ") is unavailable");
            }
            snapshots.add(new Snapshot(p.getId(), ci.getQuantity(), p.getPrice(), p));
        }

        // ── Steps 4-6: Sequential deduction (no lock in Step 7) ──────────────
        //
        // In Step 7 the whole method is @Transactional, so a SoldOutException here
        // causes an automatic rollback of any previously deducted stock.
        // Step 8 restructures: each deduction will commit under its own per-product
        // Redisson lock, and manual compensation (restoreStock) handles the unwind.
        //
        // NOTE: No cache invalidation here (Step 8 adds first-deletion + async second-deletion).

        for (Snapshot s : snapshots) {
            // Step 6c: conditional UPDATE — the MySQL safety net against overselling
            int rows = inventoryRepository.deductStock(s.productId(), s.qty());
            if (rows == 0) {
                // sold out — exception triggers @Transactional rollback, restoring prior deductions
                throw new SoldOutException(s.productId());
            }
            log.debug("[Checkout] deducted productId={} qty={}", s.productId(), s.qty());
        }

        // ── Step 7: Create order + order items + clear cart (one @Transactional block) ──
        BigDecimal total = snapshots.stream()
                .map(s -> s.price().multiply(BigDecimal.valueOf(s.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(total);
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);

        for (Snapshot s : snapshots) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(s.product());
            item.setQuantity(s.qty());
            item.setPriceAtPurchase(s.price());  // snapshot — immune to future price changes
            orderItemRepository.save(item);
        }

        cartItemRepository.deleteByCartId(cart.getId());

        // ── Step 7d: Publish in-process event; bridge forwards to Kafka AFTER_COMMIT (Step 9) ──
        eventPublisher.publishEvent(
                new OrderCreatedDomainEvent(this, order.getId(), userId, order.getCreatedAt()));

        log.info("[Checkout] order created: orderId={} userId={} total={} items={}",
                order.getId(), userId, total, snapshots.size());

        return toDetailResponse(order, orderItemRepository.findByOrderId(order.getId()));
    }

    // -------------------------------------------------------------------------
    // Pay — mock payment trigger
    // -------------------------------------------------------------------------

    /**
     * Conditional UPDATE: exactly one of {pay, expiry-cancel} wins.
     * rowsAffected = 0  → order already cancelled/paid → 409
     * rowsAffected = 1  → PAID; publishes PaymentCompletedDomainEvent AFTER_COMMIT.
     */
    @Transactional
    public OrderResponse pay(Long orderId) {
        Long userId = resolveUserId();

        // Ownership check — user can only pay their own orders (ADMIN can pay any)
        Order existing = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!isAdmin() && !existing.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }

        LocalDateTime now = LocalDateTime.now();

        // Race-safe payment — returns 0 if already PAID or CANCELLED
        int rows = orderRepository.payIfPending(orderId, now);
        if (rows == 0) {
            throw new IllegalStateException(
                    "Order " + orderId + " cannot be paid (status is not PENDING — already paid or cancelled)");
        }

        // Re-read after JPQL UPDATE (clearAutomatically=true ensures fresh state)
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        // Publish in-process event; bridge forwards to Kafka AFTER_COMMIT (Step 9)
        eventPublisher.publishEvent(
                new PaymentCompletedDomainEvent(this, order.getId(), userId, order.getPaidAt()));

        log.info("[Pay] orderId={} userId={} paidAt={}", order.getId(), userId, order.getPaidAt());

        return toDetailResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    public Page<OrderResponse> getMyOrders(Pageable pageable) {
        Long userId = resolveUserId();
        return orderRepository.findByUserId(userId, pageable).map(this::toListResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Long userId = resolveUserId();

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        // Owner check — 404 (not 403) to avoid leaking order existence to non-owners
        if (!isAdmin() && !order.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }

        return toDetailResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    protected Long resolveUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    /** List-view response — no items loaded (avoids N+1 on paginated lists). */
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

    /** Detail-view response — includes order items. */
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

    // Kept for backward compatibility with existing callers (Step 4 stubs referenced toResponse)
    protected OrderResponse toResponse(Order order) {
        return toListResponse(order);
    }
}
