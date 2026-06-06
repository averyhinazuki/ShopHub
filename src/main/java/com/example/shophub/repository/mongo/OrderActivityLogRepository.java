package com.example.shophub.repository.mongo;

import com.example.shophub.document.OrderActivityLog;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderActivityLogRepository extends MongoRepository<OrderActivityLog, String> {
}
