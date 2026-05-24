package com.example.flashsale.repository.mongo;

import com.example.flashsale.document.OrderActivityLog;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderActivityLogRepository extends MongoRepository<OrderActivityLog, String> {
}
