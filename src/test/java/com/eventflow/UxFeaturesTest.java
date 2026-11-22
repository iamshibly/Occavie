package com.eventflow;

import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.SavedEvent;
import com.eventflow.model.User;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.repository.SavedEventRepository;
import com.eventflow.repository.UserRepository;
import com.eventflow.service.EventSuggestionService;
import com.eventflow.service.IcsBuilder;
import com.eventflow.service.OrganizerProfileService;
import com.eventflow.service.SavedEventService;
import com.eventflow.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Saved events, calendar files, suggestions, organizer public pages and venue validation. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UxFeaturesTest {
    @Mock EventRepository events;
    @Mock SavedEventRepository saved;
    @Mock RegistrationRepository registrations;
    @Mock UserRepository users;
    @Mock UserService userService;

    User pat, organizer;

    private Event event(String id, String category, int daysAhead) {
        return Event.builder().id(id).title("Event " + id).category(category).status("UPCOMING").organizerId("org1")
                .date(LocalDate.now().plusDays(daysAhead)).time(LocalTime.of(10, 0)).location("Dhaka Hall").capacity(50).build();
    }

    @BeforeEach
    void setUp() {
        pat = User.builder().id("p1").fullName("Pat").email("pat@example.com").role("PARTICIPANT").build();
        organizer = User.builder().id("org1").fullName("Olive Organizer").email("private@example.com").role("ORGANIZER").build();
    }

    // ---------------------------------------------------------------- saved events

    @Test
    void savingIsIdempotentAndHandlesConcurrentDuplicates() {
        SavedEventService service = new SavedEventService(saved, events);
        when(events.findById("e1")).thenReturn(Optional.of(event("e1", "Music", 3)));
        when(saved.existsByParticipantIdAndEventId("p1", "e1")).thenReturn(false, true);
        service.save(pat, "e1");
        service.save(pat, "e1");                      // already saved: no second row
        verify(saved, times(1)).save(any(SavedEvent.class));

        when(saved.existsByParticipantIdAndEventId("p1", "e1")).thenReturn(false);
        when(saved.save(any(SavedEvent.class))).thenThrow(new DuplicateKeyException("unique index"));
        assertDoesNotThrow(() -> service.save(pat, "e1"), "a concurrent duplicate is not an error");
    }

    @Test
    void onlyParticipantsCanSaveAndOnlyPublishedEvents() {
        SavedEventService service = new SavedEventService(saved, events);
        Event draft = event("d1", "Music", 3);
        draft.setStatus("DRAFT");
        when(events.findById("d1")).thenReturn(Optional.of(draft));
        when(events.findById("e1")).thenReturn(Optional.of(event("e1", "Music", 3)));
        assertThrows(IllegalArgumentException.class, () -> service.save(pat, "d1"));
        assertThrows(IllegalArgumentException.class, () -> service.save(organizer, "e1"));
        assertThrows(IllegalArgumentException.class, () -> service.save(null, "e1"));
        assertThrows(IllegalArgumentException.class, () -> service.unsave(organizer, "e1"));
        verify(saved, never()).save(any());
    }

    @Test
    void savedListShowsSoonestFirstFinishedLastAndSkipsDeletedAndPrivateEvents() {
        SavedEventService service = new SavedEventService(saved, events);
        Event soon = event("soon", "Music", 2), later = event("later", "Music", 9), past = event("past", "Music", -3);
        past.setStatus("UPCOMING");
        Event hidden = event("hidden", "Music", 4); hidden.setStatus("PENDING_APPROVAL");
        for (Event e : List.of(soon, later, past, hidden)) when(events.findById(e.getId())).thenReturn(Optional.of(e));
        when(events.findById("gone")).thenReturn(Optional.empty());
        when(saved.findByParticipantIdOrderBySavedAtDesc("p1")).thenReturn(List.of(
                SavedEvent.builder().eventId("later").build(), SavedEvent.builder().eventId("past").build(), SavedEvent.builder().eventId("gone").build(),
                SavedEvent.builder().eventId("hidden").build(), SavedEvent.builder().eventId("soon").build()));
        assertEquals(List.of("soon", "later", "past"), service.savedEvents(pat).stream().map(Event::getId).toList());
    }

    // ---------------------------------------------------------------- calendar (ICS)

    @Test
    void icsConvertsTheEventTimeZoneToUtcAndIncludesVenueAndUrl() {
        Event e = event("e1", "Music", 3);
        e.setTitle("Tech, Talks; & More");
        e.setDescription("Line one\nLine two, with comma");
        e.setLocation("Dhaka Hall");
        e.setAddress("12 Road, Gulshan");
        e.setOrganizerName("Olive");
        e.setDate(LocalDate.of(2027, 3, 14));
        e.setTime(LocalTime.of(18, 30));
        String ics = IcsBuilder.build(e, LocalDateTime.of(2027, 3, 14, 21, 0), ZoneId.of("Asia/Dhaka"), "https://occavie.example/events/e1", Instant.parse("2027-01-01T00:00:00Z"));
        assertTrue(ics.contains("BEGIN:VCALENDAR\r\n") && ics.endsWith("END:VCALENDAR\r\n"));
        assertTrue(ics.contains("DTSTART:20270314T123000Z\r\n"), "18:30 Asia/Dhaka (UTC+6) is 12:30 UTC");
        assertTrue(ics.contains("DTEND:20270314T150000Z\r\n"));
        assertTrue(ics.contains("X-WR-TIMEZONE:Asia/Dhaka"));
        assertTrue(ics.contains("SUMMARY:Tech\\, Talks\\; & More"), "commas and semicolons are escaped");
        assertTrue(ics.contains("LOCATION:Dhaka Hall\\, 12 Road\\, Gulshan"));
        assertTrue(ics.contains("URL:https://occavie.example/events/e1"));
        assertTrue(ics.contains("Line one\\nLine two\\, with comma"), "newlines become \\n");
        assertTrue(ics.contains("UID:e1@occavie") && ics.contains("STATUS:CONFIRMED"));
    }

    @Test
    void icsLinesAreFoldedAt75OctetsAndDefaultsApply() {
        Event e = event("e1", "Music", 3);
        e.setTitle("É".repeat(60));              // 2 bytes each in UTF-8
        e.setDescription("d".repeat(300));
        String ics = IcsBuilder.build(e, null, ZoneId.of("UTC"), "https://x.example/e", Instant.now());
        for (String line : ics.split("\r\n")) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75, "line too long: " + line.length());
        }
        e.setStatus("CANCELLED");
        e.setTime(null);
        String allDay = IcsBuilder.build(e, null, ZoneId.of("UTC"), "https://x.example/e", Instant.now());
        assertTrue(allDay.contains("DTSTART;VALUE=DATE:") && allDay.contains("STATUS:CANCELLED"));
        e.setTime(LocalTime.of(9, 0));
        assertTrue(IcsBuilder.build(e, null, ZoneId.of("UTC"), "u", Instant.now()).contains("DTEND:"), "a default duration is applied");
    }

    // ---------------------------------------------------------------- suggestions

    @Test
    void suggestionsPreferSameCategoryAndSkipBookedOrSavedEvents() {
        com.eventflow.service.EventService eventService = mock(com.eventflow.service.EventService.class);
        com.eventflow.service.RegistrationService registrationService = mock(com.eventflow.service.RegistrationService.class);
        EventSuggestionService suggestions = new EventSuggestionService(eventService, registrationService, registrations, saved);
        Event current = event("cur", "Music", 5);
        Event music1 = event("m1", "Music", 6), music2 = event("m2", "Music", 8);
        Event tech1 = event("t1", "Technology", 4), design1 = event("d1", "Design", 2);
        Event registered = event("reg", "Music", 3);
        when(eventService.findAllSorted()).thenReturn(List.of(design1, registered, tech1, current, music1, music2));
        when(registrations.findByParticipantIdOrderByRegisteredAtDesc("p1")).thenReturn(List.of(Registration.builder().eventId("reg").build()));
        when(saved.findByParticipantIdOrderBySavedAtDesc("p1")).thenReturn(List.of(SavedEvent.builder().eventId("t1").build()));

        List<String> ids = suggestions.relatedTo(current, pat, 3).stream().map(r -> r.event().getId()).toList();
        assertEquals(List.of("m1", "m2"), ids.subList(0, 2), "same category first");
        assertFalse(ids.contains("cur"), "never the event itself");
        assertFalse(ids.contains("reg"), "never one already booked");
        assertFalse(ids.contains("t1"), "never one already saved");
        assertEquals(ids.size(), ids.stream().distinct().count(), "no duplicates");
        // a guest has no history, so booked events are fine to suggest
        List<String> guest = suggestions.relatedTo(current, null, 5).stream().map(r -> r.event().getId()).toList();
        assertTrue(guest.contains("reg"));
        assertFalse(guest.contains("cur"));
        assertTrue(suggestions.relatedTo(current, null, 3).stream().allMatch(r -> !r.reasons().isEmpty()), "every suggestion says why");
    }

    @Test
    void suggestionsNeverIncludeNonPublishedEvents() {
        com.eventflow.service.EventService eventService = mock(com.eventflow.service.EventService.class);
        com.eventflow.service.RegistrationService registrationService = mock(com.eventflow.service.RegistrationService.class);
        EventSuggestionService suggestions = new EventSuggestionService(eventService, registrationService, registrations, saved);
        Event pending = event("p", "Music", 2); pending.setStatus("PENDING_APPROVAL");
        Event ok = event("ok", "Music", 3);
        when(eventService.findAllSorted()).thenReturn(List.of(pending, ok));
        assertEquals(List.of("ok"), suggestions.relatedTo(event("cur", "Music", 9), null, 5).stream().map(r -> r.event().getId()).toList());
    }

    // ---------------------------------------------------------------- organizer public profiles

    @Test
    void publicPageOnlyForActiveOrganizersAndOnlyPublishedEvents() {
        OrganizerProfileService profiles = new OrganizerProfileService(users, events, userService);
        when(users.findById("org1")).thenReturn(Optional.of(organizer));
        when(users.findById("p1")).thenReturn(Optional.of(pat));
        Event upcoming = event("up", "Music", 5), past = event("past", "Music", -5), draft = event("dr", "Music", 5);
        draft.setStatus("DRAFT");
        Event pending = event("pe", "Music", 6); pending.setStatus("PENDING_APPROVAL");
        Event cancelled = event("ca", "Music", 7); cancelled.setStatus("CANCELLED");
        when(events.findByOrganizerIdOrderByDateAsc("org1")).thenReturn(List.of(past, upcoming, draft, pending, cancelled));

        var profile = profiles.publicProfile("org1").orElseThrow();
        assertEquals(List.of("up"), profile.upcoming().stream().map(Event::getId).toList(), "drafts, pending and cancelled are not listed as upcoming");
        assertEquals(List.of("past"), profile.past().stream().map(Event::getId).toList());
        assertTrue(profiles.publicProfile("p1").isEmpty(), "participants have no public page");
        assertTrue(profiles.publicProfile("nobody").isEmpty());
        organizer.setEnabled(false);
        assertTrue(profiles.publicProfile("org1").isEmpty(), "disabled accounts disappear");
    }

    @Test
    void publicProfileFieldsAreValidatedAndOnlyOrganizersMayEditThem() {
        OrganizerProfileService profiles = new OrganizerProfileService(users, events, userService);
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        profiles.updatePublicProfile(organizer, " We host meetups ", "https://olive.example", "events@olive.example");
        assertEquals("We host meetups", organizer.getBio());
        assertEquals("events@olive.example", organizer.getPublicContactEmail());
        assertEquals("private@example.com", organizer.getEmail(), "the account email is untouched and separate");
        assertThrows(IllegalArgumentException.class, () -> profiles.updatePublicProfile(organizer, "x", "javascript:alert(1)", ""));
        assertThrows(IllegalArgumentException.class, () -> profiles.updatePublicProfile(organizer, "x", "ftp://files.example", ""));
        assertThrows(IllegalArgumentException.class, () -> profiles.updatePublicProfile(organizer, "x", "", "not-an-email"));
        assertThrows(IllegalArgumentException.class, () -> profiles.updatePublicProfile(organizer, "b".repeat(301), "", ""));
        assertThrows(IllegalArgumentException.class, () -> profiles.updatePublicProfile(pat, "bio", "", ""));
        profiles.updatePublicProfile(organizer, "", "", "");
        assertNull(organizer.getBio());
        assertNull(organizer.getWebsite());
        assertNull(organizer.getPublicContactEmail());
    }

    // ---------------------------------------------------------------- venue extras

    @Test
    void onlineLinkMustBeHttpsAndAddressIsLengthLimited() throws Exception {
        var method = com.eventflow.service.EventService.class.getDeclaredMethod("validateVenueExtras", String.class, String.class);
        method.setAccessible(true);
        java.util.function.BiConsumer<String, String> check = (address, url) -> {
            try { method.invoke(null, address, url); }
            catch (java.lang.reflect.InvocationTargetException ex) { throw (RuntimeException) ex.getCause(); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        };
        assertDoesNotThrow(() -> check.accept("12 Road, Gulshan", "https://meet.example/room"));
        assertDoesNotThrow(() -> check.accept(null, ""));
        assertThrows(IllegalArgumentException.class, () -> check.accept(null, "http://insecure.example"));
        assertThrows(IllegalArgumentException.class, () -> check.accept(null, "javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> check.accept(null, "not a url"));
        assertThrows(IllegalArgumentException.class, () -> check.accept("a".repeat(161), null));
    }
}
