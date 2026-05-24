package com.example.flashsale.controller;

import com.example.flashsale.dto.OrderResponse;
import com.example.flashsale.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Step 7 : POST /api/orders/checkout   (sequential deduction, no lock)
 *          GET  /api/orders/me         (paginated; already existed as stub)
 *          GET  /api/orders/{id}       (owner-only; ADMIN can read any)
 *          POST /api/orders/{id}/pay   (conditional UPDATE race guard)
 * Step 8 : + Redisson lock + cache-aside in checkout
 * Step 11: GET  /api/orders            [ADMIN] + OrderExpiryScheduler
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** POST /api/orders/checkout — convert cart to a PENDING order */
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout() {
        return ResponseEntity.ok(orderService.checkout());
    }

    /** GET /api/orders/me — caller's own orders (paginated) */
    @GetMapping("/me")
    public ResponseEntity<Page<OrderResponse>> getMyOrders(Pageable pageable) {
        return ResponseEntity.ok(orderService.getMyOrders(pageable));
    }

    /**
     * GET /api/orders/{id} — order detail with items.
     * Owner only; ADMIN can read any. 404 (not 403) for non-owners — no info leakage.
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }

    /**
     * POST /api/orders/{id}/pay — mock payment trigger.
     * Uses conditional UPDATE (WHERE status = 'PENDING') so exactly one of
     * {/pay succeeds, OrderExpiryScheduler cancels} wins — never both.
     * Returns 409 if the order was already cancelled or paid.
     */
    @PostMapping("/{id}/pay")
    public ResponseEntity<OrderResponse> pay(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.pay(id));
    }
}
