package com.example.flashsale.repository.jpa;

import com.example.flashsale.entity.ProductInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProductInventoryRepository extends JpaRepository<ProductInventory, Long> {

    Optional<ProductInventory> findByProductId(Long productId);

    // Conditional deduction — returns 1 on success, 0 if stock insufficient (the safety net)
    @Modifying
    @Query("UPDATE ProductInventory pi SET pi.availableStock = pi.availableStock - :qty " +
           "WHERE pi.product.id = :productId AND pi.availableStock >= :qty")
    int deductStock(Long productId, int qty);

    // Restore stock — no condition needed (we are adding back, not racing to subtract)
    @Modifying
    @Query("UPDATE ProductInventory pi SET pi.availableStock = pi.availableStock + :qty " +
           "WHERE pi.product.id = :productId")
    int restoreStock(Long productId, int qty);

    // Admin inventory adjust — delta can be positive (restock) or negative (correction/write-off)
    @Modifying
    @Query("UPDATE ProductInventory pi " +
           "SET pi.availableStock = pi.availableStock + :delta, " +
           "    pi.totalStock = pi.totalStock + CASE WHEN :delta > 0 THEN :delta ELSE 0 END " +
           "WHERE pi.product.id = :productId")
    int adjustStock(Long productId, int delta);
}
