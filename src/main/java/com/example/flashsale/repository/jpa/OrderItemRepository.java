package com.example.flashsale.repository.jpa;

import com.example.flashsale.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    /**
     * Returns [productId, quantity] pairs for all items in an order.
     *
     * Used by OrderExpiryScheduler to restore stock without triggering lazy
     * loading of the Product entity. Fetching only the FK value (product.id)
     * via JPQL avoids a LazyInitializationException when called outside a session.
     */
    @Query("SELECT oi.product.id, oi.quantity FROM OrderItem oi WHERE oi.order.id = :orderId")
    List<Object[]> findProductIdAndQuantityByOrderId(Long orderId);
}
