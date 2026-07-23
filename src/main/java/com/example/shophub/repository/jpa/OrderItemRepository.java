package com.example.shophub.repository.jpa;

import com.example.shophub.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    /**
     * [productId, quantity] pairs for an order. Selecting the FK directly avoids
     * lazy-loading Product, so OrderExpiryScheduler can call it outside a session.
     */
    @Query("SELECT oi.product.id, oi.quantity FROM OrderItem oi WHERE oi.order.id = :orderId")
    List<Object[]> findProductIdAndQuantityByOrderId(Long orderId);
}
