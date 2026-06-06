package com.example.shophub.service;

import com.example.shophub.dto.cart.CartResponse;
import com.example.shophub.entity.*;
import com.example.shophub.repository.jpa.*;
import com.example.shophub.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductInventoryRepository inventoryRepository;
    @Mock SecurityUtils securityUtils;

    @InjectMocks CartService cartService;

    @Test
    void getCart_doesNotQueryInventorySeparately() {
        when(securityUtils.resolveUserId()).thenReturn(1L);

        // product with inventory already set — simulates what @EntityGraph delivers
        Product product = new Product();
        product.setId(10L);
        product.setName("Widget");
        product.setPrice(BigDecimal.valueOf(9.99));

        ProductInventory inv = new ProductInventory();
        inv.setAvailableStock(20);
        inv.setTotalStock(50);
        product.setInventory(inv);

        // cart item
        CartItem item = new CartItem();
        item.setId(1L);
        item.setProduct(product);
        item.setQuantity(2);

        // cart
        Cart cart = new Cart();
        cart.setId(1L);
        cart.setUserId(1L);
        cart.setItems(List.of(item));

        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));

        CartResponse response = cartService.getCart();

        // EntityGraph pre-loads inventory — inventoryRepository must not be called
        verifyNoInteractions(inventoryRepository);

        // sanity: correct stock value came through
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getAvailableStock()).isEqualTo(20);
    }
}
