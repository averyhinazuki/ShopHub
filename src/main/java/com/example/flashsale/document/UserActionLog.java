package com.example.flashsale.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "user_action_log")
public class UserActionLog {

    @Id
    private String id;

    private Long userId;
    private String action;         // e.g. "GET /api/products", "POST /api/orders/checkout"
    private LocalDateTime timestamp;
    private String ip;
}
