package com.example.shophub.repository.jpa;

import com.example.shophub.entity.Product;
import com.example.shophub.enums.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @EntityGraph("Product.withInventoryAndCategory")
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    @EntityGraph("Product.withInventoryAndCategory")
    Page<Product> findByStatusAndCategoryId(ProductStatus status, Long categoryId, Pageable pageable);

    @EntityGraph("Product.withInventoryAndCategory")
    Page<Product> findByStatusAndNameContainingIgnoreCase(ProductStatus status, String name, Pageable pageable);
}
