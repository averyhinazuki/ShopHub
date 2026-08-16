package com.example.shophub.controller;

import com.example.shophub.dto.OrderResponse;
import com.example.shophub.dto.order.CheckoutStatusResponse;
import com.example.shophub.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * POST /api/orders/checkout — accepts checkout asynchronously.
     * Returns 202 Accepted with { checkoutId, status: PENDING }.
     * Client polls GET /api/orders/checkout-status/{checkoutId} for the result.
     */
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutStatusResponse> checkout() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(orderService.initiateCheckout());
    }

    /**
     * GET /api/orders/checkout-status/{checkoutId} — polls async checkout result.
     *
     * Every path terminates: PENDING means keep polling, SUCCESS/FAILED means stop,
     * and 404 means the record is gone (expired, or never existed) — stop and check
     * the order list. Clients should still bound their own polling with a max
     * attempt count and backoff.
     */
    @GetMapping("/checkout-status/{checkoutId}")
    public ResponseEntity<CheckoutStatusResponse> checkoutStatus(@PathVariable String checkoutId) {
        return ResponseEntity.ok(orderService.getCheckoutStatus(checkoutId));
    }

    /** GET /api/orders/me — caller's own orders (paginated) */
    @GetMapping("/me")
    public ResponseEntity<Page<OrderResponse>> getMyOrders(Pageable pageable) {
        return ResponseEntity.ok(orderService.getMyOrders(pageable));
    }

    /**
     * GET /api/orders — all orders across all users [ADMIN only].
     * Supports ?page=&size=&sort= via Spring's Pageable resolution.
     */
    @GetMapping
    public ResponseEntity<Page<OrderResponse>> getAllOrders(Pageable pageable) {
        return ResponseEntity.ok(orderService.getAllOrders(pageable));
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
