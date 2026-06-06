package com.example.shophub.repository.jpa;

import com.example.shophub.entity.Cart;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    @EntityGraph(attributePaths = {"items.product.inventory"})
    Optional<Cart> findByUserId(Long userId);
}
