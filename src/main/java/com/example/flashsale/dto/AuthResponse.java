package com.example.flashsale.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String accessToken;   // short-lived (15m), use for all API calls
    private String refreshToken;  // long-lived (1d), use only for POST /api/auth/refresh
}
