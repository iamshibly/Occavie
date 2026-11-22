package com.eventflow.repository;

import com.eventflow.model.Announcement;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AnnouncementRepository extends MongoRepository<Announcement, String> {
    List<Announcement> findByEventIdOrderByCreatedAtDesc(String eventId);
    long countByEventIdAndCreatedAtAfter(String eventId, LocalDateTime after);
    long countByOrganizerIdAndCreatedAtAfter(String organizerId, LocalDateTime after);
}
