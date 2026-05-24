package com.example.flashsale.repository.jpa;

import com.example.flashsale.entity.Order;
import com.example.flashsale.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserId(Long userId, Pageable pageable);

    // Fetch IDs of PENDING orders older than the given cutoff (for expiry job)
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :before")
    List<Long> findExpiredOrderIds(OrderStatus status, LocalDateTime before, Pageable pageable);

    // Race-safe cancellation: returns 1 if we claimed it, 0 if already paid/cancelled
    // clearAutomatically = true so findById after this UPDATE sees the new status
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = 'CANCELLED' WHERE o.id = :id AND o.status = 'PENDING'")
    int cancelIfPending(Long id);

    // Race-safe payment: returns 1 if we claimed it, 0 if already expired/cancelled
    // clearAutomatically = true so findById after this UPDATE sees PAID + paidAt
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = 'PAID', o.paidAt = :now WHERE o.id = :id AND o.status = 'PENDING'")
    int payIfPending(Long id, LocalDateTime now);
}
