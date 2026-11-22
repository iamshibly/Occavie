package com.eventflow;

import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.model.WaitlistEntry;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.FeedbackRepository;
import com.eventflow.repository.PaymentAttemptRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.repository.WaitlistRepository;
import com.eventflow.service.CouponService;
import com.eventflow.service.NotificationService;
import com.eventflow.service.NotificationType;
import com.eventflow.service.RegistrationService;
import com.eventflow.service.TicketTokenService;
import com.eventflow.service.TicketTypeService;
import com.eventflow.service.WaitlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Waitlist: joining, ordering, offers that reserve seats, expiry, and the capacity invariant. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WaitlistTest {
    @Mock WaitlistRepository waitlistRepository;
    @Mock RegistrationRepository registrationRepository;
    @Mock EventRepository eventRepository;
    @Mock NotificationService notifications;
    @Mock MongoTemplate mongo;
    @Mock FeedbackRepository feedbackRepository;
    @Mock PaymentAttemptRepository paymentAttemptRepository;
    @Mock TicketTypeService ticketTypeService;
    @Mock CouponService couponService;

    final List<WaitlistEntry> store = new ArrayList<>();
    final AtomicLong registered = new AtomicLong();
    final AtomicInteger ids = new AtomicInteger();
    WaitlistService waitlist;
    Event event;
    User ann, ben, cat, dan;

    private User user(String id) {
        return User.builder().id(id).fullName(id + " Person").email(id + "@example.com").role("PARTICIPANT").build();
    }

    @BeforeEach
    void setUp() {
        waitlist = new WaitlistService(waitlistRepository, registrationRepository, eventRepository, notifications, mongo);
        event = Event.builder().id("e1").title("Summit").organizerId("org").status("UPCOMING").capacity(2)
                .date(LocalDate.now().plusDays(3)).time(LocalTime.of(10, 0)).build();
        ann = user("ann"); ben = user("ben"); cat = user("cat"); dan = user("dan");
        registered.set(2); // the event starts full
        when(eventRepository.findById("e1")).thenReturn(Optional.of(event));
        when(registrationRepository.countByEventId("e1")).thenAnswer(i -> registered.get());
        when(registrationRepository.existsByEventIdAndParticipantId(anyString(), anyString())).thenReturn(false);
        when(waitlistRepository.save(any(WaitlistEntry.class))).thenAnswer(i -> {
            WaitlistEntry e = i.getArgument(0);
            if (e.getId() == null) { e.setId("w" + ids.incrementAndGet()); store.add(e); }
            return e;
        });
        when(waitlistRepository.findByEventIdAndParticipantId(eq("e1"), anyString())).thenAnswer(i ->
                store.stream().filter(e -> e.getParticipantId().equals(i.getArgument(1))).findFirst());
        when(waitlistRepository.findByEventIdAndStatus(eq("e1"), anyString())).thenAnswer(i ->
                store.stream().filter(e -> e.getStatus().equals(i.getArgument(1))).toList());
        when(waitlistRepository.findByEventIdAndStatusInOrderByJoinedAtAscIdAsc(eq("e1"), anyList())).thenAnswer(i ->
                store.stream().filter(e -> ((List<String>) i.getArgument(1)).contains(e.getStatus())).toList());
        when(waitlistRepository.findByStatusAndOfferExpiresAtBefore(anyString(), any(LocalDateTime.class))).thenAnswer(i ->
                store.stream().filter(e -> e.getStatus().equals(i.getArgument(0)) && e.getOfferExpiresAt() != null
                        && e.getOfferExpiresAt().isBefore(i.getArgument(1))).toList());
    }

    private void joinInOrder(User... users) {
        LocalDateTime t = LocalDateTime.now().minusMinutes(30);
        for (User u : users) {
            waitlist.join("e1", u);
            store.get(store.size() - 1).setJoinedAt(t);   // deterministic, strictly increasing join times
            t = t.plusMinutes(1);
        }
    }

    // ---------------------------------------------------------------- joining

    @Test
    void canJoinWhenSoldOutButNotWhenSeatsAreFree() {
        assertEquals("WAITING", waitlist.join("e1", ann).getStatus());
        registered.set(1);
        var ex = assertThrows(IllegalArgumentException.class, () -> waitlist.join("e1", ben));
        assertTrue(ex.getMessage().contains("Seats are still available"));
    }

    @Test
    void cannotJoinTwiceOrWhenAlreadyRegistered() {
        waitlist.join("e1", ann);
        assertThrows(IllegalArgumentException.class, () -> waitlist.join("e1", ann));
        assertEquals(1, store.size(), "no duplicate rows");
        when(registrationRepository.existsByEventIdAndParticipantId("e1", "ben")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> waitlist.join("e1", ben));
    }

    @Test
    void onlyParticipantsCanJoinAndOnlyForBookableEvents() {
        User organizer = User.builder().id("org").role("ORGANIZER").build();
        assertThrows(IllegalArgumentException.class, () -> waitlist.join("e1", organizer));
        event.setStatus("CANCELLED");
        assertThrows(IllegalArgumentException.class, () -> waitlist.join("e1", ann));
        event.setStatus("DRAFT");
        assertThrows(IllegalArgumentException.class, () -> waitlist.join("e1", ann));
        event.setStatus("UPCOMING");
        event.setDate(LocalDate.now().minusDays(1));
        assertThrows(IllegalArgumentException.class, () -> waitlist.join("e1", ann));
    }

    @Test
    void positionsFollowJoinOrderAndStayConsistent() {
        joinInOrder(ann, ben, cat);
        assertEquals(1, waitlist.position("e1", "ann"));
        assertEquals(2, waitlist.position("e1", "ben"));
        assertEquals(3, waitlist.position("e1", "cat"));
        assertEquals(0, waitlist.position("e1", "dan"));
        waitlist.leave("e1", "ann");
        assertEquals(1, waitlist.position("e1", "ben"));
        assertEquals(2, waitlist.position("e1", "cat"));
    }

    @Test
    void rejoiningAfterLeavingGoesToTheBackOfTheLine() {
        joinInOrder(ann, ben);
        waitlist.leave("e1", "ann");
        waitlist.join("e1", ann);
        assertEquals(1, waitlist.position("e1", "ben"));
        assertEquals(2, waitlist.position("e1", "ann"));
    }

    // ---------------------------------------------------------------- promotion and reservation

    @Test
    void freedSeatIsOfferedToTheFirstInLineAndNotifiesThem() {
        joinInOrder(ann, ben);
        registered.set(1);   // someone cancelled
        assertEquals(1, waitlist.promote("e1"));
        WaitlistEntry first = store.get(0);
        assertEquals("OFFERED", first.getStatus());
        assertTrue(first.getOfferExpiresAt().isAfter(LocalDateTime.now()));
        assertEquals("WAITING", store.get(1).getStatus());
        verify(notifications).notify(eq(NotificationType.WAITLIST_OFFER), eq("ann"), eq("e1"), any(), contains("reserved for you"), contains("/events/e1"), anyString(), any());
    }

    @Test
    void offersNeverExceedFreeSeats() {
        joinInOrder(ann, ben, cat);
        registered.set(0);
        assertEquals(2, waitlist.promote("e1"), "two free seats -> two offers, not three");
        assertEquals(2, waitlist.activeOffers("e1"));
        assertEquals(0, waitlist.freeSeats(event));
        assertEquals(0, waitlist.promote("e1"), "promoting again changes nothing");
        assertEquals("WAITING", store.get(2).getStatus());
    }

    @Test
    void reservedSeatCannotBeTakenByAnotherParticipant() {
        joinInOrder(ann);
        registered.set(1);
        waitlist.promote("e1");
        assertTrue(waitlist.hasRoom(event, "ann"), "the offered participant can book");
        assertFalse(waitlist.hasRoom(event, "dan"), "everyone else sees the event as full");
        // and the real booking path enforces it
        RegistrationService registrations = new RegistrationService(registrationRepository, paymentAttemptRepository, mongo,
                eventRepository, feedbackRepository, notifications, ticketTypeService, couponService,
                new TicketTokenService("s"), waitlist);
        when(ticketTypeService.resolveForRegistration(any(), any())).thenReturn(new TicketTypeService.TicketSelection(null, "General Admission", java.math.BigDecimal.ZERO, 2));
        when(couponService.apply(any(), any(), any())).thenReturn(new CouponService.AppliedCoupon(null, null, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
        when(registrationRepository.save(any(Registration.class))).thenAnswer(i -> { registered.incrementAndGet(); return i.getArgument(0); });
        var ex = assertThrows(IllegalArgumentException.class, () -> registrations.register("e1", dan));
        assertEquals("This event is already full.", ex.getMessage());
        registrations.register("e1", ann);
        assertEquals("CLAIMED", store.get(0).getStatus());
        assertEquals(2, registered.get(), "never more registrations than capacity");
        assertEquals(0, waitlist.activeOffers("e1"));
    }

    @Test
    void expiredOfferReturnsTheSeatAndInvitesTheNextPerson() {
        joinInOrder(ann, ben);
        registered.set(1);
        waitlist.promote("e1");
        store.get(0).setOfferExpiresAt(LocalDateTime.now().minusMinutes(1));   // ann never claimed
        assertFalse(waitlist.hasRoom(event, "ann") && waitlist.validOffer("e1", "ann").isPresent(), "expired offer is no longer valid");
        waitlist.expireOffers();
        assertEquals("EXPIRED", store.get(0).getStatus());
        assertEquals("OFFERED", store.get(1).getStatus());
        verify(notifications).notify(eq(NotificationType.WAITLIST_OFFER), eq("ben"), eq("e1"), any(), contains("reserved for you"), anyString(), anyString(), any());
        assertEquals(1, waitlist.activeOffers("e1"));
    }

    @Test
    void leavingWithAHeldSeatPassesItOn() {
        joinInOrder(ann, ben);
        registered.set(1);
        waitlist.promote("e1");
        waitlist.leave("e1", "ann");
        assertEquals("LEFT", store.get(0).getStatus());
        assertEquals("OFFERED", store.get(1).getStatus());
    }

    @Test
    void cancellingTheEventClosesTheLineAndNotifiesEveryone() {
        joinInOrder(ann, ben);
        waitlist.cancelAll("e1", "Event cancelled");
        assertTrue(store.stream().allMatch(e -> e.getStatus().equals("CANCELLED")));
        verify(notifications).notify(eq(NotificationType.EVENT_CANCELLED), eq("ann"), eq("e1"), any(), eq("Event cancelled"), anyString(), anyString(), any());
        verify(notifications).notify(eq(NotificationType.EVENT_CANCELLED), eq("ben"), eq("e1"), any(), eq("Event cancelled"), anyString(), anyString(), any());
    }
}
