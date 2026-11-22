package com.eventflow.service;

import com.eventflow.model.Announcement;
import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.repository.AnnouncementRepository;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.RegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Organizer announcements.
 *
 * <p>Guard rails against abuse: only an ORGANIZER can send, only about an event they own that is open (published,
 * not cancelled, not finished), only to people registered for that event, and at most
 * {@value #PER_EVENT_PER_DAY} per event and {@value #PER_ORGANIZER_PER_DAY} per organizer in any 24 hours.
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {
    static final int PER_EVENT_PER_DAY = 3;
    static final int PER_ORGANIZER_PER_DAY = 10;

    private final AnnouncementRepository announcementRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final NotificationService notificationService;

    public Announcement send(String eventId, User organizer, String subject, String message) {
        if (organizer == null || !"ORGANIZER".equals(organizer.getRole())) {
            throw new IllegalArgumentException("Only organizers can send announcements.");
        }
        Event event = eventRepository.findById(eventId == null ? "" : eventId)
                .filter(e -> organizer.getId().equals(e.getOrganizerId()))
                .orElseThrow(() -> new IllegalArgumentException("You can only send announcements for your own events."));
        if (!EventRules.bookable(event)) {
            throw new IllegalArgumentException("Announcements can only be sent for published, upcoming events.");
        }
        String cleanSubject = clean(subject);
        String cleanMessage = clean(message);
        if (cleanSubject.length() < 3 || cleanSubject.length() > 100) throw new IllegalArgumentException("Subject must be 3-100 characters.");
        if (cleanMessage.length() < 10 || cleanMessage.length() > 1000) throw new IllegalArgumentException("Message must be 10-1000 characters.");

        LocalDateTime since = LocalDateTime.now().minusHours(24);
        if (announcementRepository.countByEventIdAndCreatedAtAfter(eventId, since) >= PER_EVENT_PER_DAY) {
            throw new IllegalArgumentException("Limit reached: at most " + PER_EVENT_PER_DAY + " announcements per event every 24 hours.");
        }
        if (announcementRepository.countByOrganizerIdAndCreatedAtAfter(organizer.getId(), since) >= PER_ORGANIZER_PER_DAY) {
            throw new IllegalArgumentException("Daily announcement limit reached. Try again tomorrow.");
        }
        List<Registration> recipients = registrationRepository.findByEventIdOrderByRegisteredAtAsc(eventId).stream()
                .filter(r -> !r.isCancelled()).toList();
        if (recipients.isEmpty()) throw new IllegalArgumentException("Nobody is registered for this event yet.");

        Announcement saved = announcementRepository.save(Announcement.builder().eventId(eventId).organizerId(organizer.getId())
                .subject(cleanSubject).message(cleanMessage).recipientCount(recipients.size()).build());
        String text = event.getTitle() + " - " + cleanSubject + ": " + cleanMessage;
        for (Registration recipient : recipients) {
            notificationService.notify(NotificationType.ANNOUNCEMENT, recipient.getParticipantId(), eventId, event.getTitle(),
                    text, "/events/" + eventId, "ANNOUNCE:" + saved.getId() + ":" + recipient.getParticipantId(),
                    List.of("From the organizer of " + event.getTitle() + ": " + cleanSubject));
        }
        return saved;
    }

    public List<Announcement> history(String eventId, User organizer) {
        Event event = eventRepository.findById(eventId == null ? "" : eventId)
                .filter(e -> organizer != null && organizer.getId().equals(e.getOrganizerId()))
                .orElseThrow(() -> new IllegalArgumentException("You can only view announcements for your own events."));
        return announcementRepository.findByEventIdOrderByCreatedAtDesc(event.getId());
    }

    /** Plain text only: control characters removed, whitespace tidied. */
    static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
