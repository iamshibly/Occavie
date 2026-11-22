package com.eventflow.repository;

import com.eventflow.model.WaitlistEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WaitlistRepository extends MongoRepository<WaitlistEntry, String> {
    Optional<WaitlistEntry> findByEventIdAndParticipantId(String eventId, String participantId);
    List<WaitlistEntry> findByEventIdAndStatus(String eventId, String status);
    List<WaitlistEntry> findByEventIdAndStatusInOrderByJoinedAtAscIdAsc(String eventId, List<String> statuses);
    List<WaitlistEntry> findByStatusAndOfferExpiresAtBefore(String status, LocalDateTime time);
    void deleteAllByEventId(String eventId);
}
