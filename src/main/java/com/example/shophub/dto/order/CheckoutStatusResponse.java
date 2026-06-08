package com.example.shophub.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutStatusResponse {
    private String checkoutId;
    private String status;        // PENDING | SUCCESS | FAILED
    private Long orderId;         // non-null on SUCCESS
    private String failureReason; // non-null on FAILED
}
