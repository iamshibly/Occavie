package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.model.User;
import com.eventflow.model.WaitlistEntry;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.repository.WaitlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Waiting list with temporary seat reservations.
 *
 * <p>Capacity rule (the only one): {@code registrations + activeOffers <= capacity}. A seat offered to a waitlisted
 * participant counts as taken until the offer is claimed or expires, so nobody can book it in the meantime and total
 * registrations never exceed capacity. Every operation that changes seats first writes the event document
 * ({@code bookingVersion}) inside the MongoDB transaction, serialising concurrent bookings, joins and promotions.
 */
@Service
@RequiredArgsConstructor
public class WaitlistService {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, h:mm a");

    private final WaitlistRepository waitlistRepository;
    private final RegistrationRepository registrationRepository;
    private final EventRepository eventRepository;
    private final NotificationService notificationService;
    private final MongoTemplate mongoTemplate;

    @Value("${occavie.waitlist.offer-hours:24}")
    private int offerHours = 24;

    // ------------------------------------------------------------------ capacity

    /** Offers that are still valid (reserved seats). */
    public long activeOffers(String eventId) {
        LocalDateTime now = LocalDateTime.now();
        return waitlistRepository.findByEventIdAndStatus(eventId, "OFFERED").stream()
                .filter(e -> e.getOfferExpiresAt() != null && e.getOfferExpiresAt().isAfter(now)).count();
    }

    public Optional<WaitlistEntry> validOffer(String eventId, String participantId) {
        if (participantId == null) return Optional.empty();
        LocalDateTime now = LocalDateTime.now();
        return waitlistRepository.findByEventIdAndParticipantId(eventId, participantId)
                .filter(e -> "OFFERED".equals(e.getStatus()) && e.getOfferExpiresAt() != null && e.getOfferExpiresAt().isAfter(now));
    }

    /** Seats nobody holds: capacity minus registrations minus reserved offers. */
    public long freeSeats(Event event) {
        return event.getCapacity() - registrationRepository.countByEventId(event.getId()) - activeOffers(event.getId());
    }

    /** Can this participant take a seat right now? Their own offer counts as available to them only. */
    public boolean hasRoom(Event event, String participantId) {
        long free = freeSeats(event) + (validOffer(event.getId(), participantId).isPresent() ? 1 : 0);
        return free > 0;
    }

    // ------------------------------------------------------------------ join / leave

    @Transactional
    public WaitlistEntry join(String eventId, User participant) {
        if (participant == null || !"PARTICIPANT".equals(participant.getRole())) {
            throw new IllegalArgumentException("Only participants can join a waitlist.");
        }
        lock(eventId);
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new IllegalArgumentException("Event not found."));
        if (!EventRules.bookable(event) || isPast(event)) {
            throw new IllegalArgumentException("This event is not open for registration.");
        }
        if (registrationRepository.existsByEventIdAndParticipantId(eventId, participant.getId())) {
            throw new IllegalArgumentException("You are already registered for this event.");
        }
        if (freeSeats(event) > 0) {
            throw new IllegalArgumentException("Seats are still available. Register for the event instead.");
        }
        Optional<WaitlistEntry> existing = waitlistRepository.findByEventIdAndParticipantId(eventId, participant.getId());
        if (existing.isPresent()) {
            WaitlistEntry entry = existing.get();
            if (entry.isActive()) throw new IllegalArgumentException("You are already on the waitlist for this event.");
            // Rejoining after leaving, expiring or cancelling puts you at the back of the line.
            entry.setStatus("WAITING");
            entry.setJoinedAt(LocalDateTime.now());
            entry.setOfferedAt(null);
            entry.setOfferExpiresAt(null);
            return waitlistRepository.save(entry);
        }
        try {
            return waitlistRepository.save(WaitlistEntry.builder().eventId(eventId).participantId(participant.getId())
                    .participantName(participant.getFullName()).participantEmail(participant.getEmail()).build());
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("You are already on the waitlist for this event.");
        }
    }

    @Transactional
    public void leave(String eventId, String participantId) {
        lock(eventId);
        WaitlistEntry entry = waitlistRepository.findByEventIdAndParticipantId(eventId, participantId)
                .filter(WaitlistEntry::isActive)
                .orElseThrow(() -> new IllegalArgumentException("You are not on the waitlist for this event."));
        boolean heldSeat = "OFFERED".equals(entry.getStatus());
        entry.setStatus("LEFT");
        waitlistRepository.save(entry);
        if (heldSeat) promote(eventId);
    }

    // ------------------------------------------------------------------ positions

    /** 1-based place in line among WAITING participants; 0 when not waiting. Same order every time: joinedAt, then id. */
    public int position(String eventId, String participantId) {
        List<WaitlistEntry> waiting = waitingInOrder(eventId);
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getParticipantId().equals(participantId)) return i + 1;
        }
        return 0;
    }

    public List<WaitlistEntry> waitingInOrder(String eventId) {
        return waitlistRepository.findByEventIdAndStatusInOrderByJoinedAtAscIdAsc(eventId, List.of("WAITING")).stream()
                .sorted(Comparator.comparing(WaitlistEntry::getJoinedAt).thenComparing(e -> e.getId() == null ? "" : e.getId()))
                .toList();
    }

    /** Organizer view: reserved offers first, then the line in order. */
    public List<WaitlistEntry> activeEntries(String eventId) {
        return waitlistRepository.findByEventIdAndStatusInOrderByJoinedAtAscIdAsc(eventId, List.of("OFFERED", "WAITING")).stream()
                .sorted(Comparator.comparing((WaitlistEntry e) -> "OFFERED".equals(e.getStatus()) ? 0 : 1)
                        .thenComparing(WaitlistEntry::getJoinedAt).thenComparing(e -> e.getId() == null ? "" : e.getId()))
                .toList();
    }

    public Optional<WaitlistEntry> entryFor(String eventId, String participantId) {
        return participantId == null ? Optional.empty() : waitlistRepository.findByEventIdAndParticipantId(eventId, participantId);
    }

    // ------------------------------------------------------------------ promotion / expiry

    /** Offers freed seats to the people at the front of the line. Returns how many offers were made. */
    @Transactional
    public int promote(String eventId) {
        lock(eventId);
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null || !EventRules.bookable(event) || isPast(event)) return 0;
        int offered = 0;
        while (freeSeats(event) > 0) {
            List<WaitlistEntry> line = waitingInOrder(eventId);
            if (line.isEmpty()) break;
            WaitlistEntry next = line.get(0);
            LocalDateTime now = LocalDateTime.now();
            next.setStatus("OFFERED");
            next.setOfferedAt(now);
            next.setOfferExpiresAt(now.plusHours(offerHours));
            waitlistRepository.save(next);
            notificationService.notify(NotificationType.WAITLIST_OFFER, next.getParticipantId(), eventId, event.getTitle(),
                    "A seat opened up for " + event.getTitle() + ". It is reserved for you until "
                            + next.getOfferExpiresAt().format(WHEN) + ". Book now to claim it.",
                    "/events/" + eventId + "#tickets", "OFFER:" + next.getId() + ":" + now.toString(),
                    List.of("The seat is held for you until " + next.getOfferExpiresAt().format(WHEN) + ".", "After that it goes to the next person in line."));
            offered++;
        }
        return offered;
    }

    /** Lapsed offers return their seat to the line and the next person is invited. */
    public void expireOffers() {
        List<WaitlistEntry> lapsed = waitlistRepository.findByStatusAndOfferExpiresAtBefore("OFFERED", LocalDateTime.now());
        Set<String> events = new java.util.LinkedHashSet<>();
        for (WaitlistEntry entry : lapsed) {
            entry.setStatus("EXPIRED");
            waitlistRepository.save(entry);
            notificationService.notifyUser(entry.getParticipantId(),
                    "Your reserved seat expired. You can rejoin the waitlist if the event is still full.",
                    "/events/" + entry.getEventId());
            events.add(entry.getEventId());
        }
        for (String eventId : events) {
            try { promote(eventId); } catch (RuntimeException ignored) { /* retried on the next run */ }
        }
    }

    // ------------------------------------------------------------------ booking hooks

    /** Called inside the booking transaction: a waitlisted participant who books leaves the line. */
    public void consumeOffer(String eventId, String participantId) {
        waitlistRepository.findByEventIdAndParticipantId(eventId, participantId).filter(WaitlistEntry::isActive).ifPresent(entry -> {
            entry.setStatus("CLAIMED");
            waitlistRepository.save(entry);
        });
    }

    /** Event cancelled: close the line and tell everyone. */
    public void cancelAll(String eventId, String message) {
        for (WaitlistEntry entry : waitlistRepository.findByEventIdAndStatusInOrderByJoinedAtAscIdAsc(eventId, List.of("WAITING", "OFFERED"))) {
            entry.setStatus("CANCELLED");
            waitlistRepository.save(entry);
            notificationService.notify(NotificationType.EVENT_CANCELLED, entry.getParticipantId(), eventId, null, message,
                    "/events/" + eventId, "WLCANCEL:" + eventId + ":" + entry.getParticipantId(), null);
        }
    }

    public void deleteAllByEvent(String eventId) {
        waitlistRepository.deleteAllByEventId(eventId);
    }

    // ------------------------------------------------------------------ helpers

    private void lock(String eventId) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(eventId)), new Update().inc("bookingVersion", 1L), Event.class);
    }

    private boolean isPast(Event event) {
        return "COMPLETED".equals(EventRules.status(event));
    }
}
