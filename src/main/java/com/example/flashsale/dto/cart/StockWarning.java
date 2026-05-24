package com.example.flashsale.dto.cart;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StockWarning {

    /** Always "STOCK_INSUFFICIENT" */
    private String type;

    /** How many units are currently available */
    private int available;

    /** How many units the user requested */
    private int requested;
}
