package com.eventflow;

import com.eventflow.controller.InternalJobsController;
import com.eventflow.model.Announcement;
import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.repository.AnnouncementRepository;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.service.AnnouncementService;
import com.eventflow.service.EmailOutboxService;
import com.eventflow.service.NotificationService;
import com.eventflow.service.NotificationType;
import com.eventflow.service.ReminderService;
import com.eventflow.service.ScheduledJobs;
import com.eventflow.service.WaitlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Organizer announcements (permissions, recipients, rate limits) and scheduled reminders / job endpoint. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnnouncementReminderTest {
    @Mock AnnouncementRepository announcements;
    @Mock EventRepository events;
    @Mock RegistrationRepository registrations;
    @Mock NotificationService notifications;
    @Mock WaitlistService waitlist;
    @Mock EmailOutboxService outbox;

    AnnouncementService service;
    ReminderService reminders;
    Event event;
    User owner, other, participant, admin;

    @BeforeEach
    void setUp() {
        service = new AnnouncementService(announcements, events, registrations, notifications);
        reminders = new ReminderService(events, registrations, notifications);
        event = Event.builder().id("e1").title("Summit").organizerId("org1").status("UPCOMING").capacity(50)
                .date(LocalDate.now().plusDays(3)).time(LocalTime.of(10, 0)).location("Dhaka Hall").build();
        owner = User.builder().id("org1").fullName("Olive").role("ORGANIZER").build();
        other = User.builder().id("org2").fullName("Other").role("ORGANIZER").build();
        participant = User.builder().id("p1").fullName("Pat").role("PARTICIPANT").build();
        admin = User.builder().id("a1").fullName("Ada").role("ADMIN").build();
        when(events.findById("e1")).thenReturn(Optional.of(event));
        when(registrations.findByEventIdOrderByRegisteredAtAsc("e1")).thenReturn(List.of(
                Registration.builder().participantId("p1").build(), Registration.builder().participantId("p2").build()));
        when(announcements.save(any(Announcement.class))).thenAnswer(i -> { Announcement a = i.getArgument(0); a.setId("a" + System.nanoTime()); return a; });
    }

    // ---------------------------------------------------------------- announcements: permissions

    @Test
    void ownerSendsToRegisteredAttendeesOnly() {
        Announcement sent = service.send("e1", owner, "Doors open", "Doors open at 9:30, bring your ticket.");
        assertEquals(2, sent.getRecipientCount());
        verify(notifications).notify(eq(NotificationType.ANNOUNCEMENT), eq("p1"), eq("e1"), eq("Summit"), contains("Doors open"), anyString(), anyString(), any());
        verify(notifications).notify(eq(NotificationType.ANNOUNCEMENT), eq("p2"), eq("e1"), eq("Summit"), contains("Doors open"), anyString(), anyString(), any());
        verify(notifications, times(2)).notify(any(), anyString(), any(), any(), any(), any(), any(), any());
    }

    private static String contains(String part) { return org.mockito.ArgumentMatchers.contains(part); }

    @Test
    void onlyTheOwningOrganizerMayAnnounce() {
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", other, "Hello all", "A message that is long enough."));
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", participant, "Hello all", "A message that is long enough."));
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", admin, "Hello all", "A message that is long enough."));
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", null, "Hello all", "A message that is long enough."));
        assertThrows(IllegalArgumentException.class, () -> service.history("e1", other));
        verify(notifications, never()).notify(any(), any(), any(), any(), any(), any(), any(), any());
        verify(announcements, never()).save(any());
    }

    @Test
    void unpublishedCancelledAndFinishedEventsCannotBeAnnounced() {
        for (String status : List.of("DRAFT", "PENDING_APPROVAL", "REJECTED", "CANCELLED")) {
            event.setStatus(status);
            assertThrows(IllegalArgumentException.class, () -> service.send("e1", owner, "Hello all", "A message that is long enough."), status);
        }
        event.setStatus("UPCOMING");
        event.setDate(LocalDate.now().minusDays(1));
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", owner, "Hello all", "A message that is long enough."));
    }

    @Test
    void validationAndEmptyAudience() {
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", owner, "x", "A message that is long enough."));
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", owner, "Hello all", "short"));
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", owner, "Hello all", "x".repeat(1001)));
        when(registrations.findByEventIdOrderByRegisteredAtAsc("e1")).thenReturn(List.of());
        var ex = assertThrows(IllegalArgumentException.class, () -> service.send("e1", owner, "Hello all", "A message that is long enough."));
        assertTrue(ex.getMessage().contains("Nobody is registered"));
    }

    @Test
    void bulkSendingIsRateLimited() {
        when(announcements.countByEventIdAndCreatedAtAfter(eq("e1"), any(LocalDateTime.class))).thenReturn(3L);
        var perEvent = assertThrows(IllegalArgumentException.class, () -> service.send("e1", owner, "Hello all", "A message that is long enough."));
        assertTrue(perEvent.getMessage().contains("Limit reached"));
        when(announcements.countByEventIdAndCreatedAtAfter(eq("e1"), any(LocalDateTime.class))).thenReturn(0L);
        when(announcements.countByOrganizerIdAndCreatedAtAfter(eq("org1"), any(LocalDateTime.class))).thenReturn(10L);
        assertThrows(IllegalArgumentException.class, () -> service.send("e1", owner, "Hello all", "A message that is long enough."));
        verify(notifications, never()).notify(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- reminders

    @Test
    void remindersGoToEachRegistrantOncePerEventWithAStableKey() {
        LocalDateTime now = LocalDateTime.now();
        event.setDate(now.plusHours(5).toLocalDate());
        event.setTime(now.plusHours(5).toLocalTime().withSecond(0).withNano(0));
        when(events.findByStatusAndDateBetween(eq("UPCOMING"), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(event));
        when(notifications.notify(eq(NotificationType.EVENT_REMINDER), anyString(), eq("e1"), any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true).thenReturn(true).thenReturn(false).thenReturn(false);   // second run: already reminded
        assertEquals(2, reminders.enqueueDueReminders(now, 24));
        assertEquals(0, reminders.enqueueDueReminders(now, 24), "running again creates nothing new");
        verify(notifications, times(2)).notify(eq(NotificationType.EVENT_REMINDER), eq("p1"), eq("e1"), any(), anyString(), anyString(), eq("REMINDER:e1:p1"), any());
    }

    @Test
    void eventsOutsideTheWindowOrNotBookableAreNotReminded() {
        LocalDateTime now = LocalDateTime.now();
        Event far = Event.builder().id("far").title("Far").status("UPCOMING").date(now.plusHours(40).toLocalDate()).time(now.plusHours(40).toLocalTime()).build();
        Event started = Event.builder().id("past").title("Past").status("UPCOMING").date(now.toLocalDate()).time(now.minusHours(1).toLocalTime()).build();
        Event cancelled = Event.builder().id("c").title("C").status("CANCELLED").date(now.plusHours(2).toLocalDate()).time(now.plusHours(2).toLocalTime()).build();
        when(events.findByStatusAndDateBetween(eq("UPCOMING"), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(far, started, cancelled));
        assertEquals(0, reminders.enqueueDueReminders(now, 24));
        verify(notifications, never()).notify(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- job endpoint + scheduler

    @Test
    void jobEndpointIsDisabledWithoutATokenAndRejectsWrongOnes() {
        ScheduledJobs jobs = mock(ScheduledJobs.class);
        when(jobs.run()).thenReturn(java.util.Map.of("emailsSent", 0));
        InternalJobsController controller = new InternalJobsController(jobs);
        ReflectionTestUtils.setField(controller, "token", "");
        assertEquals(HttpStatus.NOT_FOUND, controller.run("anything").getStatusCode(), "off unless a token is configured");
        ReflectionTestUtils.setField(controller, "token", "s3cret-token");
        assertEquals(HttpStatus.FORBIDDEN, controller.run(null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.run("wrong").getStatusCode());
        verify(jobs, never()).run();
        assertEquals(HttpStatus.OK, controller.run("s3cret-token").getStatusCode());
        verify(jobs, times(1)).run();
    }

    @Test
    void oneFailingStepDoesNotStopTheOthers() {
        when(outbox.dispatchDue(anyInt())).thenReturn(3);
        doThrow(new RuntimeException("db down")).when(waitlist).expireOffers();
        ScheduledJobs jobs = new ScheduledJobs(reminders, waitlist, outbox);
        var result = jobs.run();
        assertEquals(-1, result.get("waitlistOffersReleased"));
        assertEquals(3, result.get("emailsSent"), "email delivery still ran");
    }
}
