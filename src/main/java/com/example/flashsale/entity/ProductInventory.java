package com.example.flashsale.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "product_inventory")
public class ProductInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", unique = true, nullable = false)
    private Product product;

    // Total units ever stocked; only increases via admin restock
    @Column(nullable = false)
    private Integer totalStock;

    // Units currently buyable; decreases on checkout, increases on rollback/restock
    @Column(nullable = false)
    private Integer availableStock;
}
