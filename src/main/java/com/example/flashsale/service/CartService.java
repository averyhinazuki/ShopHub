package com.example.flashsale.service;

import com.example.flashsale.dto.cart.*;
import com.example.flashsale.entity.Cart;
import com.example.flashsale.entity.CartItem;
import com.example.flashsale.entity.Product;
import com.example.flashsale.entity.ProductInventory;
import com.example.flashsale.exception.ResourceNotFoundException;
import com.example.flashsale.repository.jpa.CartItemRepository;
import com.example.flashsale.repository.jpa.CartRepository;
import com.example.flashsale.repository.jpa.ProductInventoryRepository;
import com.example.flashsale.repository.jpa.ProductRepository;
import com.example.flashsale.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository          cartRepository;
    private final CartItemRepository      cartItemRepository;
    private final ProductRepository       productRepository;
    private final ProductInventoryRepository inventoryRepository;
    private final UserRepository          userRepository;

    // ── Public API ───────────────────────────────────────────────────────────

    /** GET /api/cart — returns the caller's full cart. */
    @Transactional(readOnly = true)
    public CartResponse getCart() {
        Long userId = resolveUserId();
        Cart cart = findCartOrThrow(userId);
        return toCartResponse(cart);
    }

    /**
     * POST /api/cart/items — upsert behaviour:
     *   - product already in cart  → increment quantity by request.quantity
     *   - product not in cart      → add new CartItem
     * Soft stock check: if total quantity > availableStock, item is saved
     * but response includes a STOCK_INSUFFICIENT warning.
     */
    @Transactional
    public AddToCartResponse addItem(CartItemRequest request) {
        Long userId = resolveUserId();
        Cart cart = findCartOrThrow(userId);
        Product product = findActiveProductOrThrow(request.getProductId());

        // Upsert: find existing or create new
        CartItem item = cartItemRepository
                .findByCartIdAndProductId(cart.getId(), product.getId())
                .orElseGet(() -> {
                    CartItem ci = new CartItem();
                    ci.setCart(cart);
                    ci.setProduct(product);
                    ci.setQuantity(0);
                    return ci;
                });

        int newQty = item.getQuantity() + request.getQuantity();
        item.setQuantity(newQty);
        cartItemRepository.save(item);

        // Touch updatedAt
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);

        log.info("[Cart] user:{} addItem product:{} qty:{} → totalQty:{}", userId, product.getId(), request.getQuantity(), newQty);

        return buildAddToCartResponse(item, product, newQty);
    }

    /**
     * PUT /api/cart/items/{itemId} — set an item's quantity to the given value.
     * Ownership check: the item must belong to the caller's cart.
     * Soft stock check: same logic as addItem.
     */
    @Transactional
    public AddToCartResponse updateItem(Long itemId, CartItemRequest request) {
        Long userId = resolveUserId();
        Cart cart = findCartOrThrow(userId);
        CartItem item = findItemAndVerifyOwnership(itemId, cart.getId());

        Product product = item.getProduct();
        int newQty = request.getQuantity();
        item.setQuantity(newQty);
        cartItemRepository.save(item);

        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);

        log.info("[Cart] user:{} updateItem itemId:{} product:{} newQty:{}", userId, itemId, product.getId(), newQty);

        return buildAddToCartResponse(item, product, newQty);
    }

    /**
     * DELETE /api/cart/items/{itemId} — remove item from cart.
     * Ownership check enforced.
     */
    @Transactional
    public void removeItem(Long itemId) {
        Long userId = resolveUserId();
        Cart cart = findCartOrThrow(userId);
        CartItem item = findItemAndVerifyOwnership(itemId, cart.getId());

        cartItemRepository.delete(item);
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);

        log.info("[Cart] user:{} removeItem itemId:{}", userId, itemId);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Long resolveUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private Cart findCartOrThrow(Long userId) {
        return cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user: " + userId));
    }

    private Product findActiveProductOrThrow(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
    }

    private CartItem findItemAndVerifyOwnership(Long itemId, Long cartId) {
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + itemId));
        if (!item.getCart().getId().equals(cartId)) {
            throw new ResourceNotFoundException("Cart item not found: " + itemId);
        }
        return item;
    }

    /**
     * Soft stock check + response builder.
     * The item has already been saved — we only decide whether to attach a warning.
     */
    private AddToCartResponse buildAddToCartResponse(CartItem item, Product product, int requestedQty) {
        ProductInventory inv = inventoryRepository.findByProductId(product.getId()).orElse(null);
        int available = (inv != null) ? inv.getAvailableStock() : 0;

        CartItemResponse itemResponse = toItemResponse(item, product, available);

        AddToCartResponse response = new AddToCartResponse();
        response.setCartItem(itemResponse);

        if (requestedQty > available) {
            response.setWarning(new StockWarning("STOCK_INSUFFICIENT", available, requestedQty));
            log.warn("[Cart] STOCK_INSUFFICIENT product:{} available={} requested={}", product.getId(), available, requestedQty);
        }

        return response;
    }

    private CartResponse toCartResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(ci -> {
                    ProductInventory inv = inventoryRepository.findByProductId(ci.getProduct().getId()).orElse(null);
                    int available = (inv != null) ? inv.getAvailableStock() : 0;
                    return toItemResponse(ci, ci.getProduct(), available);
                })
                .collect(Collectors.toList());

        CartResponse res = new CartResponse();
        res.setCartId(cart.getId());
        res.setUserId(cart.getUserId());
        res.setItems(items);
        res.setUpdatedAt(cart.getUpdatedAt());
        return res;
    }

    private CartItemResponse toItemResponse(CartItem item, Product product, int available) {
        CartItemResponse res = new CartItemResponse();
        res.setId(item.getId());
        res.setProductId(product.getId());
        res.setProductName(product.getName());
        res.setPrice(product.getPrice());
        res.setImageUrl(product.getImageUrl());
        res.setQuantity(item.getQuantity());
        res.setAvailableStock(available);
        return res;
    }
}
