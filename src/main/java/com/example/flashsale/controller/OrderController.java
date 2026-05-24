package com.example.flashsale.controller;

import com.example.flashsale.dto.OrderResponse;
import com.example.flashsale.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Step 7 : POST /api/orders/checkout   (sequential deduction, no lock yet)
// Step 8 : + Redisson lock + cache-aside
// Step 9 : POST /api/orders/{id}/pay   (Kafka async payment)
// Step 11: GET  /api/orders            [ADMIN]
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/me")
    public ResponseEntity<Page<OrderResponse>> getMyOrders(Pageable pageable) {
        return ResponseEntity.ok(orderService.getMyOrders(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }
}
