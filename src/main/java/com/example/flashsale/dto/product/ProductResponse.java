package com.example.flashsale.dto.product;

import com.example.flashsale.enums.ProductStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductResponse {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Long categoryId;
    private String categoryName;
    private String imageUrl;
    private ProductStatus status;
    private Integer availableStock;   // live stock — source of truth is MySQL, may be cached
    private Integer totalStock;       // total ever stocked (sold = total - available)
    private LocalDateTime createdAt;
}
