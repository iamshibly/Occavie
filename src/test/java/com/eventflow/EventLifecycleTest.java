package com.eventflow;

import com.eventflow.dto.EventDraftForm;
import com.eventflow.dto.EventForm;
import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.repository.EventImageRepository;
import com.eventflow.repository.EventRepository;
import com.eventflow.service.CouponService;
import com.eventflow.service.EventGalleryService;
import com.eventflow.service.EventProgramService;
import com.eventflow.service.EventService;
import com.eventflow.service.NotificationService;
import com.eventflow.service.NotificationType;
import com.eventflow.service.RegistrationService;
import com.eventflow.service.TicketTypeService;
import com.eventflow.service.WaitlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Drafts, publishing, duplication, cancellation, rescheduling notifications and capacity changes. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventLifecycleTest {
    @Mock EventRepository eventRepository;
    @Mock EventImageRepository imageRepository;
    @Mock RegistrationService registrationService;
    @Mock NotificationService notifications;
    @Mock EventProgramService programService;
    @Mock TicketTypeService ticketTypeService;
    @Mock CouponService couponService;
    @Mock EventGalleryService galleryService;
    @Mock WaitlistService waitlistService;

    EventService service;
    User owner, stranger;
    Event published;

    @BeforeEach
    void setUp() {
        service = new EventService(eventRepository, imageRepository, registrationService, notifications, programService,
                ticketTypeService, couponService, galleryService, waitlistService);
        owner = User.builder().id("org1").fullName("Olive").role("ORGANIZER").build();
        stranger = User.builder().id("org2").fullName("Sam").role("ORGANIZER").build();
        published = Event.builder().id("e1").title("Summit").category("Technology").location("Dhaka Hall")
                .description("A full-day gathering for technology enthusiasts.").capacity(100).status("UPCOMING")
                .organizerId("org1").date(LocalDate.now().plusDays(10)).time(LocalTime.of(10, 0)).build();
        when(eventRepository.findById("e1")).thenReturn(Optional.of(published));
        when(eventRepository.save(any(Event.class))).thenAnswer(i -> {
            Event e = i.getArgument(0);
            if (e.getId() == null) e.setId("new1");
            return e;
        });
        when(registrationService.countByEvent("e1")).thenReturn(5L);
        when(registrationService.findByEvent("e1")).thenReturn(List.of(
                Registration.builder().participantId("p1").build(), Registration.builder().participantId("p2").build()));
    }

    private EventDraftForm draft(String title, String category, String capacity, String date, String time, String location, String description) {
        return new EventDraftForm(title, category, capacity, date, time, location, description, null, null, null, null, null);
    }

    private EventForm edit(String date, String time, String location, int capacity) {
        return new EventForm("Summit", "Technology", location, date, time, "A full-day gathering for technology enthusiasts.",
                capacity, "FREE", "", null, null, null);
    }

    // ---------------------------------------------------------------- drafts

    @Test
    void draftsStartPrivateWithoutADate() {
        Event draft = service.createDraft(draft("My Event", "Music", "50", null, null, null, null), owner);
        assertEquals("DRAFT", draft.getStatus());
        assertNull(draft.getDate());
        assertEquals("org1", draft.getOrganizerId());
        assertEquals(50, draft.getCapacity());
        assertThrows(IllegalArgumentException.class, () -> service.createDraft(draft("", "Music", "50", null, null, null, null), owner));
    }

    @Test
    void draftStepsSaveOnlyWhatWasFilledInAndCanBeResumed() {
        Event draft = Event.builder().id("d1").title("Draft").category("Music").capacity(10).status("DRAFT").organizerId("org1").build();
        when(eventRepository.findById("d1")).thenReturn(Optional.of(draft));
        String date = LocalDate.now().plusDays(5).toString();
        service.updateDraft("d1", draft(null, null, null, date, "18:30", "Banani Club", null), owner);
        assertEquals("Draft", draft.getTitle(), "untouched fields stay as they were");
        assertEquals(LocalDate.parse(date), draft.getDate());
        assertEquals(LocalTime.of(18, 30), draft.getTime());
        assertEquals("Banani Club", draft.getLocation());
        assertThrows(IllegalArgumentException.class,
                () -> service.updateDraft("d1", draft(null, null, null, LocalDate.now().minusDays(1).toString(), null, null, null), owner));
        assertThrows(IllegalArgumentException.class, () -> service.updateDraft("d1", draft(null, "Nonsense", null, null, null, null, null), owner));
    }

    @Test
    void onlyTheOwnerCanEditOrPublishADraftAndPublishedEventsAreNotDrafts() {
        Event draft = Event.builder().id("d1").title("Draft").status("DRAFT").organizerId("org1").build();
        when(eventRepository.findById("d1")).thenReturn(Optional.of(draft));
        assertThrows(IllegalArgumentException.class, () -> service.updateDraft("d1", draft("Hacked", null, null, null, null, null, null), stranger));
        assertThrows(IllegalArgumentException.class, () -> service.publish("d1", stranger));
        assertThrows(IllegalArgumentException.class, () -> service.updateDraft("e1", draft("X Y Z", null, null, null, null, null, null), owner));
        assertThrows(IllegalArgumentException.class, () -> service.publish("e1", owner));
    }

    @Test
    void publishNeedsEverythingRequiredThenGoesLive() {
        Event draft = Event.builder().id("d1").title("Draft event").category("Music").capacity(10).status("DRAFT").organizerId("org1").build();
        when(eventRepository.findById("d1")).thenReturn(Optional.of(draft));
        var ex = assertThrows(IllegalArgumentException.class, () -> service.publish("d1", owner));
        assertTrue(ex.getMessage().contains("Date and time") && ex.getMessage().contains("Venue") && ex.getMessage().contains("Description"));
        assertEquals("DRAFT", draft.getStatus());
        draft.setDate(LocalDate.now().plusDays(7)); draft.setTime(LocalTime.of(9, 0));
        draft.setLocation("City Hall"); draft.setDescription("An evening of live music and community.");
        assertTrue(service.readiness(draft).isEmpty());
        assertEquals("UPCOMING", service.publish("d1", owner).getStatus());
    }

    // ---------------------------------------------------------------- duplication

    @Test
    void duplicateCreatesAPrivateDraftWithTheProgramButNoDate() {
        Event copy = service.duplicate("e1", owner);
        assertEquals("DRAFT", copy.getStatus());
        assertEquals("Copy of Summit", copy.getTitle());
        assertNull(copy.getDate());
        assertEquals(100, copy.getCapacity());
        assertEquals("Dhaka Hall", copy.getLocation());
        verify(ticketTypeService).copyAll("e1", "new1");
        verify(programService).copyProgram("e1", "new1");
        verify(registrationService, never()).findByEvent("new1");   // bookings are never copied
        assertThrows(IllegalArgumentException.class, () -> service.duplicate("e1", stranger));
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    void cancellingKeepsTheEventAndNotifiesRegistrantsAndWaitlist() {
        Event cancelled = service.cancelOwned("e1", "Venue unavailable", owner);
        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("Venue unavailable", cancelled.getCancellationReason());
        verify(notifications).notify(eq(NotificationType.EVENT_CANCELLED), eq("p1"), eq("e1"), any(), contains("cancelled"), anyString(), anyString(), any());
        verify(notifications).notify(eq(NotificationType.EVENT_CANCELLED), eq("p2"), eq("e1"), any(), contains("cancelled"), anyString(), anyString(), any());
        verify(waitlistService).cancelAll(eq("e1"), contains("cancelled"));
        verify(couponService).pauseAllByEvent("e1");
        verify(eventRepository, never()).delete(any());
        assertThrows(IllegalArgumentException.class, () -> service.cancelOwned("e1", null, owner), "already cancelled");
        assertThrows(IllegalArgumentException.class, () -> service.updateOwned("e1", edit(LocalDate.now().plusDays(12).toString(), "10:00", "Dhaka Hall", 100), owner),
                "cancelled events are read-only");
    }

    @Test
    void cancellationRulesAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> service.cancelOwned("e1", "x", stranger));
        assertThrows(IllegalArgumentException.class, () -> service.cancelOwned("e1", "y".repeat(201), owner));
        published.setDate(LocalDate.now().minusDays(1));
        assertThrows(IllegalArgumentException.class, () -> service.cancelOwned("e1", null, owner), "completed events cannot be cancelled");
        published.setStatus("DRAFT");
        published.setDate(LocalDate.now().plusDays(3));
        assertThrows(IllegalArgumentException.class, () -> service.cancelOwned("e1", null, owner), "drafts are deleted, not cancelled");
    }

    // ---------------------------------------------------------------- rescheduling and capacity

    @Test
    void reschedulingAndVenueChangesNotifyRegisteredParticipants() {
        String newDate = LocalDate.now().plusDays(20).toString();
        service.updateOwned("e1", edit(newDate, "14:00", "Dhaka Hall", 100), owner);
        verify(notifications).notify(eq(NotificationType.EVENT_RESCHEDULED), eq("p1"), eq("e1"), any(), contains("rescheduled"), anyString(), anyString(), any());
        verify(notifications).notify(eq(NotificationType.EVENT_RESCHEDULED), eq("p2"), eq("e1"), any(), contains("rescheduled"), anyString(), anyString(), any());

        clearInvocations(notifications);
        service.updateOwned("e1", edit(newDate, "14:00", "New Venue", 100), owner);
        verify(notifications, times(2)).notify(eq(NotificationType.EVENT_RESCHEDULED), anyString(), eq("e1"), any(), contains("venue is now New Venue"), anyString(), anyString(), any());
    }

    @Test
    void minorEditsDoNotSpamParticipants() {
        service.updateOwned("e1", edit(published.getDate().toString(), "10:00", "Dhaka Hall", 100), owner);
        verify(notifications, never()).notify(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void capacityCannotDropBelowRegistrationsButIncreasePromotesTheWaitlist() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateOwned("e1", edit(published.getDate().toString(), "10:00", "Dhaka Hall", 4), owner));
        assertTrue(ex.getMessage().contains("lower than the current number of registrations"));
        verify(waitlistService, never()).promote(anyString());

        service.updateOwned("e1", edit(published.getDate().toString(), "10:00", "Dhaka Hall", 150), owner);
        assertEquals(150, published.getCapacity());
        verify(waitlistService).promote("e1");
    }

    @Test
    void draftsAreNeverCountedAsUpcomingOrPublic() {
        Event draft = Event.builder().id("d").status("DRAFT").date(LocalDate.now().plusDays(3)).build();
        assertFalse(service.isUpcoming(draft));
        assertFalse(com.eventflow.service.EventRules.isPublished(draft));
        Event cancelled = Event.builder().id("c").status("CANCELLED").date(LocalDate.now().plusDays(3)).build();
        assertFalse(service.isUpcoming(cancelled));
        assertEquals("CANCELLED", com.eventflow.service.EventRules.status(cancelled));
        assertFalse(com.eventflow.service.EventRules.bookable(cancelled));
        assertTrue(com.eventflow.service.EventRules.bookable(published));
    }
}
