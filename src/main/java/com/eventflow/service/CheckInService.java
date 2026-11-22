package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.RegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Server-side ticket verification and attendee check-in.
 *
 * <p>Every operation re-checks that the caller is an ORGANIZER who owns the event. The check-in itself is a single
 * atomic findAndModify guarded by {@code checkedInAt == null}, so two simultaneous scans of the same ticket can
 * never both succeed.
 */
@Service
@RequiredArgsConstructor
public class CheckInService {

    public enum Status { SUCCESS, ALREADY_CHECKED_IN, INVALID, WRONG_EVENT, CANCELLED, FORBIDDEN }

    /** Only what the door staff need: no phone number, no full email, no database ids. */
    public record Attendee(String name, String email, String ticketType, String reference) { }

    public record Stats(long total, long checkedIn, long remaining) { }

    public record Result(Status status, String message, Attendee attendee, LocalDateTime checkedInAt, Stats stats) { }

    public record Row(String registrationId, String name, String email, String ticketType, boolean checkedIn, LocalDateTime checkedInAt) { }

    private final RegistrationRepository registrationRepository;
    private final EventRepository eventRepository;
    private final MongoTemplate mongoTemplate;
    private final TicketTokenService tokens;

    // -------------------------------------------------------------------- scan

    public Result checkInByToken(String eventId, String token, User organizer) {
        Optional<Event> event = authorizedEvent(eventId, organizer);
        if (event.isEmpty()) return new Result(Status.FORBIDDEN, "You cannot check in attendees for this event.", null, null, null);
        Result blocked = eventBlock(event.get());
        if (blocked != null) return blocked;

        Optional<Registration> registration = tokens.codeFrom(token).flatMap(registrationRepository::findByTicketCode);
        if (registration.isEmpty()) {
            return result(Status.INVALID, "This ticket is not valid.", null, null, eventId);
        }
        return checkIn(event.get(), registration.get(), organizer);
    }

    /** Manual fallback: check in a registration the organizer found through {@link #search}. */
    public Result checkInRegistration(String eventId, String registrationId, User organizer) {
        Optional<Event> event = authorizedEvent(eventId, organizer);
        if (event.isEmpty()) return new Result(Status.FORBIDDEN, "You cannot check in attendees for this event.", null, null, null);
        Result blocked = eventBlock(event.get());
        if (blocked != null) return blocked;
        Optional<Registration> registration = registrationRepository.findById(registrationId == null ? "" : registrationId);
        if (registration.isEmpty()) return result(Status.INVALID, "This ticket is not valid.", null, null, eventId);
        return checkIn(event.get(), registration.get(), organizer);
    }

    private Result checkIn(Event event, Registration registration, User organizer) {
        String eventId = event.getId();
        if (!eventId.equals(registration.getEventId())) {
            // Do not reveal who the ticket belongs to when it is for another event.
            return result(Status.WRONG_EVENT, "This ticket is for a different event.", null, null, eventId);
        }
        if (registration.isCancelled()) {
            return result(Status.CANCELLED, "This registration was cancelled.", null, null, eventId);
        }
        LocalDateTime now = LocalDateTime.now();
        Registration updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(registration.getId())
                        .and("checkedInAt").is(null)
                        .and("status").ne("CANCELLED")),
                new Update().set("checkedInAt", now).set("checkedInBy", organizer.getId()),
                FindAndModifyOptions.options().returnNew(true), Registration.class);
        if (updated == null) {
            // Someone else won the race (or it was already scanned): report the original time.
            Registration current = registrationRepository.findById(registration.getId()).orElse(registration);
            return result(Status.ALREADY_CHECKED_IN, "Already checked in.", attendee(current), current.getCheckedInAt(), eventId);
        }
        return result(Status.SUCCESS, "Checked in.", attendee(updated), updated.getCheckedInAt(), eventId);
    }

    // ---------------------------------------------------------------- lookup

    public List<Row> search(String eventId, String query, User organizer) {
        if (authorizedEvent(eventId, organizer).isEmpty()) return List.of();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.length() < 2) return List.of();
        return registrationRepository.findByEventIdOrderByRegisteredAtAsc(eventId).stream()
                .filter(r -> !r.isCancelled())
                .filter(r -> contains(r.getParticipantName(), q) || contains(r.getParticipantEmail(), q)
                        || contains(r.getPaymentReference(), q) || contains(r.getTicketTypeName(), q))
                .limit(20)
                .map(r -> new Row(r.getId(), r.getParticipantName(), mask(r.getParticipantEmail()),
                        r.getTicketTypeName(), r.getCheckedInAt() != null, r.getCheckedInAt()))
                .toList();
    }

    public Optional<Stats> stats(String eventId, User organizer) {
        return authorizedEvent(eventId, organizer).map(e -> statsFor(eventId));
    }

    private Stats statsFor(String eventId) {
        long total = registrationRepository.countByEventId(eventId);
        long checkedIn = registrationRepository.countByEventIdAndCheckedInAtNotNull(eventId);
        return new Stats(total, checkedIn, Math.max(0, total - checkedIn));
    }

    // --------------------------------------------------------------- helpers

    /** Only ORGANIZERs, and only for events they own. */
    Optional<Event> authorizedEvent(String eventId, User organizer) {
        if (organizer == null || !"ORGANIZER".equals(organizer.getRole()) || eventId == null) return Optional.empty();
        return eventRepository.findById(eventId)
                .filter(e -> e.getOrganizerId() != null && e.getOrganizerId().equals(organizer.getId()));
    }

    private Result eventBlock(Event event) {
        String status = EventRules.status(event);
        if ("CANCELLED".equals(status)) return result(Status.CANCELLED, "This event was cancelled.", null, null, event.getId());
        if ("DRAFT".equals(status)) return result(Status.INVALID, "This event is not published.", null, null, event.getId());
        return null;
    }

    private Result result(Status status, String message, Attendee attendee, LocalDateTime at, String eventId) {
        return new Result(status, message, attendee, at, statsFor(eventId));
    }

    private Attendee attendee(Registration r) {
        return new Attendee(r.getParticipantName(), mask(r.getParticipantEmail()), r.getTicketTypeName(), shortRef(r));
    }

    private String shortRef(Registration r) {
        String code = r.getTicketCode();
        return code == null ? "" : "OV-" + code.substring(0, Math.min(8, code.length())).toUpperCase(Locale.ROOT);
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    /** "pat@example.com" -> "p**@example.com" */
    static String mask(String email) {
        if (email == null || !email.contains("@")) return "";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        return (local.isEmpty() ? "" : local.charAt(0)) + "***" + email.substring(at);
    }
}
