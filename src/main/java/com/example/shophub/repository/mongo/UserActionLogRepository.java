package com.example.shophub.repository.mongo;

import com.example.shophub.document.UserActionLog;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserActionLogRepository extends MongoRepository<UserActionLog, String> {
}
