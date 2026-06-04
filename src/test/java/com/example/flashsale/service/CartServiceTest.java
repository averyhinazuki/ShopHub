package com.example.flashsale.service;

import com.example.flashsale.dto.cart.CartResponse;
import com.example.flashsale.entity.*;
import com.example.flashsale.repository.jpa.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
    @Mock UserRepository userRepository;

    @InjectMocks CartService cartService;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCart_doesNotQueryInventorySeparately() {
        // user
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

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
