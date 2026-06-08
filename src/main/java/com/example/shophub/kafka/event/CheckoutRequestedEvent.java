package com.example.shophub.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequestedEvent {
    private String checkoutId;
    private Long userId;
    private LocalDateTime requestedAt;
}
