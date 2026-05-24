package com.example.flashsale.dto.cart;

import lombok.Data;

/**
 * Returned by POST /api/cart/items and PUT /api/cart/items/{itemId}.
 *
 * If the requested quantity exceeds current available stock, the item is
 * still saved (soft check) and a {@link StockWarning} is included.
 * {@code warning} is null when stock is sufficient.
 */
@Data
public class AddToCartResponse {

    private CartItemResponse cartItem;

    /** Non-null only when requested quantity exceeds available stock. */
    private StockWarning warning;
}
