package com.example.shophub.repository.jpa;

import com.example.shophub.entity.Order;
import com.example.shophub.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserId(Long userId, Pageable pageable);

    // IDs of PENDING orders older than the cutoff, for the expiry job.
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :before")
    List<Long> findExpiredOrderIds(OrderStatus status, LocalDateTime before, Pageable pageable);

    // Race-safe cancel: 1 if claimed, 0 if already paid/cancelled. @Transactional so
    // callers without an outer tx (OrderExpiryScheduler) still commit atomically;
    // clearAutomatically so a following findById sees the new status.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = 'CANCELLED' WHERE o.id = :id AND o.status = 'PENDING'")
    int cancelIfPending(Long id);

    // Race-safe pay: 1 if claimed, 0 if already expired/cancelled. clearAutomatically
    // so a following findById sees PAID + paidAt.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = 'PAID', o.paidAt = :now WHERE o.id = :id AND o.status = 'PENDING'")
    int payIfPending(Long id, LocalDateTime now);
}
