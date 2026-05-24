package com.example.flashsale.dto.product;

import com.example.flashsale.enums.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateProductRequest {

    @Size(max = 200)
    private String name;

    @Size(max = 1000)
    private String description;

    @DecimalMin("0.01")
    private BigDecimal price;

    private Long categoryId;

    private String imageUrl;

    // INACTIVE acts as soft-delete: product hidden from listings, kept for order history
    private ProductStatus status;
}
