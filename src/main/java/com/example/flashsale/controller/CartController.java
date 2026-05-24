package com.example.flashsale.controller;

import com.example.flashsale.dto.cart.AddToCartResponse;
import com.example.flashsale.dto.cart.CartItemRequest;
import com.example.flashsale.dto.cart.CartResponse;
import com.example.flashsale.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /** Returns the caller's current cart with all items and live stock. */
    @GetMapping
    public ResponseEntity<CartResponse> getCart() {
        return ResponseEntity.ok(cartService.getCart());
    }

    /**
     * Add a product to the cart (or increment if already present).
     * Responds with the saved item; includes STOCK_INSUFFICIENT warning if qty > availableStock.
     */
    @PostMapping("/items")
    public ResponseEntity<AddToCartResponse> addItem(@Valid @RequestBody CartItemRequest request) {
        return ResponseEntity.ok(cartService.addItem(request));
    }

    /**
     * Set the quantity of an existing cart item.
     * Responds with the updated item; includes STOCK_INSUFFICIENT warning if qty > availableStock.
     */
    @PutMapping("/items/{itemId}")
    public ResponseEntity<AddToCartResponse> updateItem(@PathVariable Long itemId,
                                                        @Valid @RequestBody CartItemRequest request) {
        return ResponseEntity.ok(cartService.updateItem(itemId, request));
    }

    /** Remove an item from the cart. */
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> removeItem(@PathVariable Long itemId) {
        cartService.removeItem(itemId);
        return ResponseEntity.noContent().build();
    }
}
