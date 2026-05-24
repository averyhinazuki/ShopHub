package com.example.flashsale.dto;

import com.example.flashsale.dto.order.OrderItemResponse;
import com.example.flashsale.enums.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderResponse {
    private Long id;
    private Long userId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    // Populated on GET /api/orders/{id} (detail view); null on list views
    private List<OrderItemResponse> items;
}
