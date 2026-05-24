package com.example.flashsale.service;

import com.example.flashsale.dto.OrderResponse;
import com.example.flashsale.entity.Order;
import com.example.flashsale.exception.ResourceNotFoundException;
import com.example.flashsale.repository.jpa.OrderRepository;
import com.example.flashsale.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

// Step 7 : checkout(), getMyOrders(), getOrder()  — sequential stock deduction
// Step 8 : + Redisson lock + cache-aside on checkout
// Step 9 : pay()                                  — Kafka async payment
// Step 11: OrderExpiryScheduler                   — background cancellation
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository  userRepository;

    public Page<OrderResponse> getMyOrders(Pageable pageable) {
        Long userId = resolveUserId();
        return orderRepository.findByUserId(userId, pageable).map(this::toResponse);
    }

    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return toResponse(order);
    }

    // --- helpers ---

    protected Long resolveUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    protected OrderResponse toResponse(Order order) {
        OrderResponse res = new OrderResponse();
        res.setId(order.getId());
        res.setUserId(order.getUserId());
        res.setStatus(order.getStatus());
        res.setTotalAmount(order.getTotalAmount());
        res.setCreatedAt(order.getCreatedAt());
        res.setPaidAt(order.getPaidAt());
        return res;
    }
}
