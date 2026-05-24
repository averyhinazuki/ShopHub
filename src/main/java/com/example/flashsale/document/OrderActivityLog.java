package com.example.flashsale.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Document(collection = "order_activity_log")
public class OrderActivityLog {

    @Id
    private String id;

    private Long orderId;
    private Long userId;
    private String event;          // e.g. "ORDER_CREATED", "PAYMENT_COMPLETED", "EXPIRED_CANCELLED"
    private LocalDateTime timestamp;
    private Map<String, Object> metadata;
}
