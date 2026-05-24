package com.example.flashsale.dto.product;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateProductRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @Size(max = 1000)
    private String description;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal price;

    @NotNull
    private Long categoryId;

    private String imageUrl;

    @NotNull
    @Min(0)
    private Integer initialStock;   // seeds both totalStock and availableStock
}
