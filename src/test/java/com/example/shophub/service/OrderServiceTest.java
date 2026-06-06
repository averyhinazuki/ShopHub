package com.example.shophub.service;

import com.example.shophub.entity.*;
import com.example.shophub.enums.ProductStatus;
import com.example.shophub.exception.ResourceNotFoundException;
import com.example.shophub.repository.jpa.*;
import com.example.shophub.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock OrderRepository            orderRepository;
    @Mock OrderItemRepository        orderItemRepository;
    @Mock CartRepository             cartRepository;
    @Mock CartItemRepository         cartItemRepository;
    @Mock ProductInventoryRepository inventoryRepository;
    @Mock ProductRepository          productRepository;
    @Mock ApplicationEventPublisher  eventPublisher;
    @Mock RedissonClient             redissonClient;
    @Mock ProductCacheService        cacheService;
    @Mock SecurityUtils              securityUtils;
    @Mock RLock                      lock;

    @InjectMocks OrderService orderService;

    @BeforeEach
    void setUp() {
        // Inject the real instance as self so internal @Transactional calls run directly
        ReflectionTestUtils.setField(orderService, "self", orderService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));
        when(securityUtils.resolveUserId()).thenReturn(1L);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Product activeProduct(Long id) {
        Product p = new Product();
        p.setId(id);
        p.setName("Product-" + id);
        p.setPrice(BigDecimal.valueOf(10.00));
        p.setStatus(ProductStatus.ACTIVE);
        return p;
    }

    private Cart cart() {
        Cart c = new Cart();
        c.setId(1L);
        c.setUserId(1L);
        return c;
    }

    private CartItem cartItem(Cart cart, Product product, int qty) {
        CartItem ci = new CartItem();
        ci.setId(product.getId() * 10);
        ci.setCart(cart);
        ci.setProduct(product);
        ci.setQuantity(qty);
        return ci;
    }

    private Order pendingOrder(Long userId) {
        Order o = new Order();
        o.setId(50L);
        o.setUserId(userId);
        o.setStatus(com.example.shophub.enums.OrderStatus.PENDING);
        o.setTotalAmount(BigDecimal.valueOf(20.00));
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }

    private List<OrderItem> singleOrderItem(Product product) {
        OrderItem oi = new OrderItem();
        oi.setId(1L);
        Order o = new Order();
        o.setId(100L);
        oi.setOrder(o);
        oi.setProduct(product);
        oi.setQuantity(2);
        oi.setPriceAtPurchase(product.getPrice());
        return List.of(oi);
    }

    // ── loadCartSnapshot ──────────────────────────────────────────────────────

    @Test
    void loadCartSnapshot_emptyCart_throwsIllegalArgument() {
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart()));
        when(cartItemRepository.findByCartId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.loadCartSnapshot(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void loadCartSnapshot_inactiveProduct_throwsIllegalArgument() {
        Cart c = cart();
        Product inactive = activeProduct(10L);
        inactive.setStatus(ProductStatus.INACTIVE);

        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(c));
        when(cartItemRepository.findByCartId(1L)).thenReturn(List.of(cartItem(c, inactive, 1)));

        assertThatThrownBy(() -> orderService.loadCartSnapshot(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void loadCartSnapshot_activeProduct_doesNotThrow() {
        Cart c = cart();
        Product p = activeProduct(10L);

        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(c));
        when(cartItemRepository.findByCartId(1L)).thenReturn(List.of(cartItem(c, p, 3)));

        assertThatNoException().isThrownBy(() -> orderService.loadCartSnapshot(1L));
        verify(cartItemRepository).findByCartId(1L);
    }

    // ── checkout ──────────────────────────────────────────────────────────────

    @Test
    void checkout_lockTimeout_throwsRuntimeException_noCompensation() throws Exception {
        doReturn(false).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        Cart c = cart();
        Product p = activeProduct(10L);
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(c));
        when(cartItemRepository.findByCartId(1L)).thenReturn(List.of(cartItem(c, p, 2)));

        assertThatThrownBy(() -> orderService.checkout())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Could not acquire lock");

        // Nothing was deducted before the lock failure — no compensation expected
        verify(inventoryRepository, never()).restoreStock(anyLong(), anyInt());
    }

    @Test
    void checkout_secondItemSoldOut_throwsSoldOutException_andCompensatesFirst() throws Exception {
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        Cart c = cart();
        Product p1 = activeProduct(10L);
        Product p2 = activeProduct(20L);
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(c));
        when(cartItemRepository.findByCartId(1L))
                .thenReturn(List.of(cartItem(c, p1, 2), cartItem(c, p2, 1)));

        when(inventoryRepository.deductStock(10L, 2)).thenReturn(1); // p1 succeeds
        when(inventoryRepository.deductStock(20L, 1)).thenReturn(0); // p2 sold out

        assertThatThrownBy(() -> orderService.checkout())
                .isInstanceOf(com.example.shophub.exception.SoldOutException.class);

        // p1's deduction must be compensated; p2 was never deducted
        verify(inventoryRepository).restoreStock(10L, 2);
        verify(inventoryRepository, never()).restoreStock(eq(20L), anyInt());
        verify(cacheService, atLeastOnce()).deleteCache(10L);
    }

    @Test
    void checkout_persistOrderFails_compensatesAllDeductions() throws Exception {
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        Cart c = cart();
        Product p1 = activeProduct(10L);
        Product p2 = activeProduct(20L);
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(c));
        when(cartItemRepository.findByCartId(1L))
                .thenReturn(List.of(cartItem(c, p1, 2), cartItem(c, p2, 3)));
        when(inventoryRepository.deductStock(anyLong(), anyInt())).thenReturn(1);

        // persistOrder fails at order save — both deductions have already been committed
        when(orderRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> orderService.checkout())
                .isInstanceOf(RuntimeException.class);

        // Both deductions must be compensated
        verify(inventoryRepository).restoreStock(10L, 2);
        verify(inventoryRepository).restoreStock(20L, 3);
    }

    @Test
    void checkout_success_returnsOrderResponse() throws Exception {
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        Cart c = cart();
        Product p = activeProduct(10L);
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(c));
        when(cartItemRepository.findByCartId(1L)).thenReturn(List.of(cartItem(c, p, 2)));
        when(inventoryRepository.deductStock(10L, 2)).thenReturn(1);

        // Give the saved order an ID so persistOrder can reference it
        doAnswer(inv -> { Order o = inv.getArgument(0); o.setId(100L); return o; })
                .when(orderRepository).save(any(Order.class));
        when(productRepository.getReferenceById(10L)).thenReturn(p);
        when(orderItemRepository.findByOrderId(100L)).thenReturn(singleOrderItem(p));

        com.example.shophub.dto.OrderResponse response = orderService.checkout();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(100L);
        assertThat(response.getItems()).hasSize(1);
        verify(inventoryRepository, never()).restoreStock(anyLong(), anyInt());
    }

    // ── pay ───────────────────────────────────────────────────────────────────

    @Test
    void pay_success_returnsOrderResponse() {
        Order order = pendingOrder(1L);
        // findById is called twice in pay(): once for ownership check, once after payIfPending
        when(orderRepository.findById(50L)).thenReturn(Optional.of(order));
        when(orderRepository.payIfPending(eq(50L), any(LocalDateTime.class))).thenReturn(1);
        when(orderItemRepository.findByOrderId(50L)).thenReturn(List.of());

        com.example.shophub.dto.OrderResponse response = orderService.pay(50L);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(50L);
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void pay_orderNotFound_throwsResourceNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.pay(99L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(orderRepository, never()).payIfPending(anyLong(), any());
    }

    @Test
    void pay_alreadyPaidOrCancelled_throwsIllegalState() {
        Order order = pendingOrder(1L);
        when(orderRepository.findById(50L)).thenReturn(Optional.of(order));
        when(orderRepository.payIfPending(eq(50L), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> orderService.pay(50L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be paid");
    }

    @Test
    void pay_otherUsersOrder_throwsResourceNotFound() {
        Order order = pendingOrder(2L); // belongs to user 2, caller is user 1
        when(orderRepository.findById(50L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.pay(50L))
                .isInstanceOf(ResourceNotFoundException.class);

        // payIfPending must NOT be called — we should have rejected before reaching it
        verify(orderRepository, never()).payIfPending(anyLong(), any());
    }
}
