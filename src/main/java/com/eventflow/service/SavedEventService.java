package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.model.SavedEvent;
import com.eventflow.model.User;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.SavedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/** Participants bookmark published events. One row per (participant, event), enforced by a unique index. */
@Service
@RequiredArgsConstructor
public class SavedEventService {
    private final SavedEventRepository savedEventRepository;
    private final EventRepository eventRepository;

    public void save(User participant, String eventId) {
        requireParticipant(participant);
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new IllegalArgumentException("Event not found."));
        if (!EventRules.isPublished(event)) throw new IllegalArgumentException("This event is not available.");
        if (savedEventRepository.existsByParticipantIdAndEventId(participant.getId(), eventId)) return;   // idempotent
        try {
            savedEventRepository.save(SavedEvent.builder().participantId(participant.getId()).eventId(eventId).build());
        } catch (DuplicateKeyException ignored) {
            // a concurrent click already saved it
        }
    }

    public void unsave(User participant, String eventId) {
        requireParticipant(participant);
        savedEventRepository.deleteByParticipantIdAndEventId(participant.getId(), eventId);
    }

    public boolean isSaved(User participant, String eventId) {
        return participant != null && "PARTICIPANT".equals(participant.getRole())
                && savedEventRepository.existsByParticipantIdAndEventId(participant.getId(), eventId);
    }

    /** Saved events that still exist and are public, soonest first (finished events last). */
    public List<Event> savedEvents(User participant) {
        requireParticipant(participant);
        return savedEventRepository.findByParticipantIdOrderBySavedAtDesc(participant.getId()).stream()
                .map(s -> eventRepository.findById(s.getEventId()).orElse(null))
                .filter(e -> e != null && EventRules.isPublished(e))
                .sorted(java.util.Comparator.comparing((Event e) -> "COMPLETED".equals(EventRules.status(e)) ? 1 : 0)
                        .thenComparing(Event::getDate, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    public void deleteAllByEvent(String eventId) {
        savedEventRepository.deleteAllByEventId(eventId);
    }

    private void requireParticipant(User user) {
        if (user == null || !"PARTICIPANT".equals(user.getRole())) {
            throw new IllegalArgumentException("Only participants can save events.");
        }
    }
}
