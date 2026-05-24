package com.example.flashsale.dto.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class InventoryAdjustRequest {

    // Positive = restock, negative = correction / write-off
    @NotNull
    private Integer delta;

    @NotBlank
    @Pattern(regexp = "restock|correction|damaged",
             message = "reason must be restock, correction, or damaged")
    private String reason;
}
