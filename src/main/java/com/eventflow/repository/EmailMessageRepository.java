package com.eventflow.repository;

import com.eventflow.model.EmailMessage;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EmailMessageRepository extends MongoRepository<EmailMessage, String> {
    Optional<EmailMessage> findByDedupeKey(String dedupeKey);
    long countByStatus(String status);
}
