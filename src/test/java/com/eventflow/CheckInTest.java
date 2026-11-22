package com.eventflow;

import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.service.CheckInService;
import com.eventflow.service.CheckInService.Status;
import com.eventflow.service.TicketTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Check-in: valid, invalid, cancelled, duplicate and unauthorized scenarios, plus token integrity. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckInTest {
    @Mock RegistrationRepository registrations;
    @Mock EventRepository events;
    @Mock MongoTemplate mongo;

    TicketTokenService tokens = new TicketTokenService("unit-test-secret");
    CheckInService service;
    Event event;
    User organizer, otherOrganizer, participant, admin;
    Registration registration;
    String token;

    @BeforeEach
    void setUp() {
        service = new CheckInService(registrations, events, mongo, tokens);
        event = Event.builder().id("e1").title("Summit").organizerId("org1").status("UPCOMING")
                .date(LocalDate.now().plusDays(2)).time(LocalTime.of(10, 0)).capacity(50).build();
        organizer = User.builder().id("org1").fullName("Olive").email("o@example.com").role("ORGANIZER").build();
        otherOrganizer = User.builder().id("org2").fullName("Other").email("x@example.com").role("ORGANIZER").build();
        participant = User.builder().id("p1").fullName("Pat").email("pat@example.com").role("PARTICIPANT").build();
        admin = User.builder().id("a1").fullName("Ada").email("a@example.com").role("ADMIN").build();
        String code = tokens.newCode();
        token = tokens.tokenFor(code);
        registration = Registration.builder().id("507f1f77bcf86cd799439011").eventId("e1").participantId("p1")
                .participantName("Pat Participant").participantEmail("pat@example.com").ticketTypeName("VIP")
                .ticketCode(code).build();
        when(events.findById("e1")).thenReturn(Optional.of(event));
        when(registrations.findByTicketCode(code)).thenReturn(Optional.of(registration));
        when(registrations.findById(registration.getId())).thenReturn(Optional.of(registration));
        when(registrations.countByEventId("e1")).thenReturn(10L);
        when(registrations.countByEventIdAndCheckedInAtNotNull("e1")).thenReturn(4L);
    }

    private void atomicUpdateSucceeds() {
        Registration updated = Registration.builder().id(registration.getId()).eventId("e1").participantName("Pat Participant")
                .participantEmail("pat@example.com").ticketTypeName("VIP").ticketCode(registration.getTicketCode())
                .checkedInAt(LocalDateTime.now()).checkedInBy("org1").build();
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Registration.class)))
                .thenReturn(updated);
    }

    @Test
    void validTicketChecksInAndShowsOnlyMinimalAttendeeData() {
        atomicUpdateSucceeds();
        var result = service.checkInByToken("e1", token, organizer);
        assertEquals(Status.SUCCESS, result.status());
        assertEquals("Pat Participant", result.attendee().name());
        assertEquals("VIP", result.attendee().ticketType());
        assertEquals("p***@example.com", result.attendee().email(), "email must be masked");
        assertNotNull(result.checkedInAt());
        assertEquals(10, result.stats().total());
        assertEquals(6, result.stats().remaining());
        assertFalse(result.attendee().reference().contains(registration.getId()), "database id must not be exposed");
    }

    @Test
    void checkInIsAnAtomicConditionalUpdate() {
        atomicUpdateSucceeds();
        service.checkInByToken("e1", token, organizer);
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Registration.class));
        String json = query.getValue().getQueryObject().toJson();
        assertTrue(json.contains("checkedInAt"), "only registrations without a check-in time can be updated");
        assertTrue(json.contains("status"), "cancelled registrations are excluded");
    }

    @Test
    void secondScanReportsAlreadyCheckedInWithOriginalTime() {
        LocalDateTime first = LocalDateTime.now().minusMinutes(5);
        registration.setCheckedInAt(first);
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Registration.class)))
                .thenReturn(null);   // the conditional update matched nothing: someone already checked in
        var result = service.checkInByToken("e1", token, organizer);
        assertEquals(Status.ALREADY_CHECKED_IN, result.status());
        assertEquals(first, result.checkedInAt());
    }

    @Test
    void invalidAndTamperedTokensAreRejected() {
        assertEquals(Status.INVALID, service.checkInByToken("e1", "garbage", organizer).status());
        assertEquals(Status.INVALID, service.checkInByToken("e1", null, organizer).status());
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        assertEquals(Status.INVALID, service.checkInByToken("e1", tampered, organizer).status());
        // a validly signed token whose code matches no registration
        assertEquals(Status.INVALID, service.checkInByToken("e1", tokens.tokenFor(tokens.newCode()), organizer).status());
        // the bare registration code / database id is not a ticket
        assertEquals(Status.INVALID, service.checkInByToken("e1", registration.getTicketCode(), organizer).status());
        assertEquals(Status.INVALID, service.checkInByToken("e1", registration.getId(), organizer).status());
    }

    @Test
    void cancelledRegistrationAndCancelledEventAreRejected() {
        registration.setStatus("CANCELLED");
        assertEquals(Status.CANCELLED, service.checkInByToken("e1", token, organizer).status());
        registration.setStatus(null);
        event.setStatus("CANCELLED");
        assertEquals(Status.CANCELLED, service.checkInByToken("e1", token, organizer).status());
    }

    @Test
    void ticketForAnotherEventIsRejectedWithoutRevealingTheAttendee() {
        registration.setEventId("someOtherEvent");
        var result = service.checkInByToken("e1", token, organizer);
        assertEquals(Status.WRONG_EVENT, result.status());
        assertNull(result.attendee());
    }

    @Test
    void onlyTheOwningOrganizerMayCheckIn() {
        assertEquals(Status.FORBIDDEN, service.checkInByToken("e1", token, otherOrganizer).status());
        assertEquals(Status.FORBIDDEN, service.checkInByToken("e1", token, participant).status());
        assertEquals(Status.FORBIDDEN, service.checkInByToken("e1", token, admin).status());
        assertEquals(Status.FORBIDDEN, service.checkInByToken("e1", token, null).status());
        assertEquals(Status.FORBIDDEN, service.checkInRegistration("e1", registration.getId(), otherOrganizer).status());
        assertTrue(service.search("e1", "pat", otherOrganizer).isEmpty());
        assertTrue(service.stats("e1", otherOrganizer).isEmpty());
    }

    @Test
    void manualLookupFindsAttendeesAndMasksEmail() {
        when(registrations.findByEventIdOrderByRegisteredAtAsc("e1")).thenReturn(List.of(registration));
        var rows = service.search("e1", "pat", organizer);
        assertEquals(1, rows.size());
        assertEquals("p***@example.com", rows.get(0).email());
        assertFalse(rows.get(0).checkedIn());
        assertTrue(service.search("e1", "p", organizer).isEmpty(), "needs at least two characters");
        atomicUpdateSucceeds();
        assertEquals(Status.SUCCESS, service.checkInRegistration("e1", registration.getId(), organizer).status());
    }

    @Test
    void tokensAreUnpredictableSignedAndSecretBound() {
        String a = tokens.newCode(), b = tokens.newCode();
        assertNotEquals(a, b);
        assertEquals(22, a.length());
        assertEquals(Optional.of(a), tokens.codeFrom(tokens.tokenFor(a)));
        assertTrue(new TicketTokenService("a-different-secret").codeFrom(tokens.tokenFor(a)).isEmpty(),
                "a token signed with another secret is rejected");
        assertTrue(tokens.codeFrom("OVT1." + a + ".AAAAAAAAAAAAAAAAAAAAAA").isEmpty());
    }
}
