package com.example.flashsale.repository.mongo;

import com.example.flashsale.document.UserActionLog;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserActionLogRepository extends MongoRepository<UserActionLog, String> {
}
