package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.SavedEvent;
import com.eventflow.model.User;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.repository.SavedEventRepository;
import com.eventflow.service.RecommendationEngine.Recommendation;
import com.eventflow.service.RecommendationEngine.Signal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds {@link RecommendationEngine} with real data: the events a participant saved or booked, every upcoming event and how
 * many seats are taken (one aggregation query). Results carry plain-English reasons shown next to each suggestion.
 */
@Service
@RequiredArgsConstructor
public class EventSuggestionService {
    private final EventService eventService;
    private final RegistrationService registrationService;
    private final RegistrationRepository registrationRepository;
    private final SavedEventRepository savedEventRepository;

    /** "You might also like" on an event page. */
    public List<Recommendation> relatedTo(Event event, User viewer, int limit) {
        List<Event> all = eventService.findAllSorted();
        Map<String, Event> byId = index(all);
        return RecommendationEngine.related(event, upcoming(all), RecommendationEngine.profile(signals(viewer, byId)),
                counts(all), LocalDate.now(), limit);
    }

    /** "Recommended for you" on the participant dashboard. */
    public List<Recommendation> forParticipant(User participant, int limit) {
        List<Event> all = eventService.findAllSorted();
        Map<String, Event> byId = index(all);
        return RecommendationEngine.rank(upcoming(all), RecommendationEngine.profile(signals(participant, byId)),
                counts(all), LocalDate.now(), limit);
    }

    private List<Event> upcoming(List<Event> all) {
        LocalDate today = LocalDate.now();
        return all.stream().filter(e -> e.getDate() != null && !e.getDate().isBefore(today)).toList();
    }

    private Map<String, Long> counts(List<Event> all) {
        LocalDate today = LocalDate.now();
        return registrationService.countsForEvents(all.stream().filter(e -> e.getDate() != null && !e.getDate().isBefore(today))
                .map(Event::getId).toList());
    }

    private Map<String, Event> index(List<Event> all) {
        Map<String, Event> byId = new HashMap<>();
        for (Event e : all) byId.put(e.getId(), e);
        return byId;
    }

    /** Registered events first (stronger signal), then saved events; most recent first within each. */
    private List<Signal> signals(User user, Map<String, Event> byId) {
        List<Signal> out = new ArrayList<>();
        if (user == null || !"PARTICIPANT".equals(user.getRole())) return out;
        for (Registration r : registrationRepository.findByParticipantIdOrderByRegisteredAtDesc(user.getId())) {
            Event e = byId.get(r.getEventId());
            if (e != null) out.add(new Signal(e, "registered"));
        }
        for (SavedEvent s : savedEventRepository.findByParticipantIdOrderBySavedAtDesc(user.getId())) {
            Event e = byId.get(s.getEventId());
            if (e != null) out.add(new Signal(e, "saved"));
        }
        return out;
    }
}
