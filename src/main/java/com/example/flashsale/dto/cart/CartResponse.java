package com.example.flashsale.dto.cart;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CartResponse {

    private Long cartId;
    private Long userId;
    private List<CartItemResponse> items;
    private LocalDateTime updatedAt;
}
