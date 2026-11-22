package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.RegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Finds events starting soon and reminds their attendees. It is stateless: "already reminded" lives in MongoDB as
 * the notification dedupe key, so running it every minute, twice at once, or after a restart never sends a
 * second reminder, and a missed run is made up for on the next one.
 */
@Service
@RequiredArgsConstructor
public class ReminderService {
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final NotificationService notificationService;

    /** @return number of new reminders created */
    public int enqueueDueReminders(LocalDateTime now, int hoursBefore) {
        LocalDateTime horizon = now.plusHours(hoursBefore);
        int created = 0;
        for (Event event : eventRepository.findByStatusAndDateBetween("UPCOMING", now.toLocalDate(), horizon.toLocalDate())) {
            if (!EventRules.bookable(event) || event.getDate() == null) continue;
            LocalDateTime start = LocalDateTime.of(event.getDate(), event.getTime() == null ? LocalTime.of(9, 0) : event.getTime());
            if (!start.isAfter(now) || start.isAfter(horizon)) continue;
            List<Registration> registrations = registrationRepository.findByEventIdOrderByRegisteredAtAsc(event.getId());
            for (Registration registration : registrations) {
                if (registration.isCancelled()) continue;
                boolean isNew = notificationService.notify(NotificationType.EVENT_REMINDER, registration.getParticipantId(), event.getId(), event.getTitle(),
                        event.getTitle() + " starts soon: " + event.getDate() + (event.getTime() == null ? "" : " at " + event.getTime())
                                + (event.getLocation() == null ? "" : " · " + event.getLocation()) + ".",
                        "/events/" + event.getId(),
                        "REMINDER:" + event.getId() + ":" + registration.getParticipantId(),
                        List.of("When: " + event.getDate() + (event.getTime() == null ? "" : " " + event.getTime()),
                                "Where: " + (event.getLocation() == null ? "see event page" : event.getLocation())));
                if (isNew) created++;
            }
        }
        return created;
    }
}
