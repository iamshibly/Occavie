package com.eventflow.repository;

import com.eventflow.model.SavedEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SavedEventRepository extends MongoRepository<SavedEvent, String> {
    boolean existsByParticipantIdAndEventId(String participantId, String eventId);
    List<SavedEvent> findByParticipantIdOrderBySavedAtDesc(String participantId);
    long deleteByParticipantIdAndEventId(String participantId, String eventId);
    void deleteAllByEventId(String eventId);
}
