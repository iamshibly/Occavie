package com.eventflow;

import com.eventflow.model.Event;
import com.eventflow.model.EventTicketType;
import com.eventflow.service.ReadinessEngine;
import com.eventflow.service.ReadinessEngine.Check;
import com.eventflow.service.ReadinessEngine.Inputs;
import com.eventflow.service.ReadinessEngine.Readiness;
import com.eventflow.service.ReadinessEngine.Status;
import com.eventflow.service.ReadinessService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessEngineTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private static Event fullEvent() {
        return Event.builder().id("e1").title("Flutter Mobile Dev Day").category("Technology").location("Bangladesh Startup Hub")
                .address("House 12, Road 5, Banani, Dhaka").date(TODAY.plusDays(20)).time(LocalTime.of(10, 0)).capacity(100)
                .description("A hands-on day for developers who want to ship their first mobile app. You will build a small app, meet mentors and leave with a working project. Bring a laptop.")
                .imageUrl("/images/event-photos/flutter-mobile-dev-day-2.webp").status("UPCOMING").pricingType("PAID").basePrice(BigDecimal.valueOf(400)).organizerId("o1").build();
    }

    private static EventTicketType ticket(String name, int price, int capacity) {
        return EventTicketType.builder().eventId("e1").name(name).price(BigDecimal.valueOf(price)).capacity(capacity).active(true).build();
    }

    private static Inputs fullInputs() {
        return new Inputs(List.of(ticket("Standard", 400, 80), ticket("VIP", 800, 20)), 3, 2, 1, 4, 10, 1, TODAY);
    }

    private static Check check(Readiness r, String id) { return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(); }

    @Test
    void aCompleteEventScoresFullMarksAndHasNothingToFix() {
        Readiness r = ReadinessEngine.assess(fullEvent(), fullInputs());
        assertEquals(100, r.score());
        assertEquals("Excellent", r.level());
        assertTrue(r.topTips().isEmpty());
        assertEquals(100, r.checks().stream().mapToInt(Check::max).sum(), "weights add up to 100");
    }

    @Test
    void aBareDraftScoresLowAndTheBiggestGapsComeFirst() {
        Event bare = Event.builder().id("d1").title("My event").status("DRAFT").capacity(50).build();
        Readiness r = ReadinessEngine.assess(bare, Inputs.empty(TODAY));
        assertTrue(r.score() < 30, "score " + r.score());
        assertEquals("Needs work", r.level());
        assertEquals("low", r.tone());
        assertEquals("description", r.topTips().get(0).id(), "description (16 points) is the biggest gap");
        assertEquals("cover", r.topTips().get(1).id());
        assertEquals(3, r.topTips().size());
    }

    @Test
    void coverPhotoMustBeARealOne() {
        Event e = fullEvent(); e.setImageUrl("/images/event-visual.png");
        assertEquals(Status.MISSING, check(ReadinessEngine.assess(e, fullInputs()), "cover").status());
        e.setImageUrl(null);
        assertEquals(Status.MISSING, check(ReadinessEngine.assess(e, fullInputs()), "cover").status());
    }

    @Test
    void titleAdviceCoversShortLongAndShouting() {
        Event e = fullEvent();
        e.setTitle("Party"); assertEquals(Status.IMPROVE, check(ReadinessEngine.assess(e, fullInputs()), "title").status());
        e.setTitle("ANNUAL TECHNOLOGY CONFERENCE"); assertTrue(check(ReadinessEngine.assess(e, fullInputs()), "title").tip().contains("CAPS"));
        e.setTitle("x".repeat(90)); assertTrue(check(ReadinessEngine.assess(e, fullInputs()), "title").tip().contains("Shorten"));
        e.setTitle(""); assertEquals(Status.MISSING, check(ReadinessEngine.assess(e, fullInputs()), "title").status());
    }

    @Test
    void ticketAndCapacityAdviceIsSpecific() {
        Event paid = fullEvent();
        Readiness none = ReadinessEngine.assess(paid, new Inputs(List.of(), 3, 2, 1, 4, 0, 0, TODAY));
        assertEquals(Status.MISSING, check(none, "tickets").status(), "a paid event needs priced ticket types");
        Event free = fullEvent(); free.setPricingType("FREE"); free.setBasePrice(BigDecimal.ZERO);
        assertEquals(Status.IMPROVE, check(ReadinessEngine.assess(free, new Inputs(List.of(), 3, 2, 1, 4, 0, 0, TODAY)), "tickets").status());
        Readiness mismatch = ReadinessEngine.assess(paid, new Inputs(List.of(ticket("Standard", 400, 60)), 3, 2, 1, 4, 0, 0, TODAY));
        Check cap = check(mismatch, "capacity");
        assertEquals(Status.IMPROVE, cap.status());
        assertTrue(cap.tip().contains("60") && cap.tip().contains("100"), cap.tip());
        Readiness zeroPrice = ReadinessEngine.assess(paid, new Inputs(List.of(ticket("Standard", 0, 100)), 3, 2, 1, 4, 0, 0, TODAY));
        assertEquals(Status.IMPROVE, check(zeroPrice, "tickets").status());
    }

    @Test
    void scheduleAndSpeakerAdvice() {
        Event e = fullEvent();
        assertEquals(Status.MISSING, check(ReadinessEngine.assess(e, new Inputs(fullInputs().ticketTypes(), 0, 0, 1, 4, 0, 0, TODAY)), "schedule").status());
        assertEquals(Status.IMPROVE, check(ReadinessEngine.assess(e, new Inputs(fullInputs().ticketTypes(), 2, 0, 1, 4, 0, 0, TODAY)), "schedule").status());
        assertEquals(Status.MISSING, check(ReadinessEngine.assess(e, new Inputs(fullInputs().ticketTypes(), 3, 0, 1, 4, 0, 0, TODAY)), "speakers").status());
    }

    @Test
    void venueAndDateAdvice() {
        Event e = fullEvent(); e.setAddress(null);
        assertEquals(Status.IMPROVE, check(ReadinessEngine.assess(e, fullInputs()), "venue").status());
        e.setOnlineUrl("https://meet.example/abc");
        assertEquals(Status.DONE, check(ReadinessEngine.assess(e, fullInputs()), "venue").status(), "an online link is a valid venue");
        e.setTime(null);
        assertEquals(Status.IMPROVE, check(ReadinessEngine.assess(e, fullInputs()), "date").status());
        e.setTime(LocalTime.NOON); e.setDate(TODAY.minusDays(3)); e.setStatus("DRAFT");
        assertTrue(check(ReadinessEngine.assess(e, fullInputs()), "date").tip().contains("past"));
    }

    @Test
    void levelsFollowTheScore() {
        Event e = fullEvent();
        int full = ReadinessEngine.assess(e, fullInputs()).score();
        assertEquals(100, full);
        e.setImageUrl(null); e.setDescription("short");           // -14 -16
        Readiness r = ReadinessEngine.assess(e, fullInputs());
        assertEquals(70, r.score()); assertEquals("Almost there", r.level()); assertFalse(r.ready());
    }

    @Test
    void growthTipsReactToBookingsAndTheCalendar() {
        Event soon = fullEvent(); soon.setDate(TODAY.plusDays(5));
        var lowBookings = ReadinessEngine.insights(soon, new Inputs(List.of(), 0, 0, 0, 0, 8, 0, TODAY), "/organizer/events/e1");
        assertTrue(lowBookings.stream().anyMatch(i -> i.text().contains("8% booked") && i.link().endsWith("/announcements")));
        assertTrue(lowBookings.stream().anyMatch(i -> i.link().equals("/organizer/coupons")), "a paid event with no coupon gets the coupon tip");
        var noBookings = ReadinessEngine.insights(soon, new Inputs(List.of(), 0, 0, 0, 0, 0, 0, TODAY), "/organizer/events/e1");
        assertTrue(noBookings.stream().anyMatch(i -> i.text().startsWith("No bookings yet with 5 days to go")));
        var almostFull = ReadinessEngine.insights(soon, new Inputs(List.of(), 0, 0, 0, 0, 95, 1, TODAY), "/organizer/events/e1");
        assertTrue(almostFull.stream().anyMatch(i -> i.text().contains("Nearly full") && i.link().endsWith("/waitlist")));
        Event past = fullEvent(); past.setDate(TODAY.minusDays(2));
        assertTrue(ReadinessEngine.insights(past, fullInputs(), "/x").isEmpty(), "no growth tips for events that already happened");
    }

    @Test
    void fixLinksPointToTheRightScreen() {
        Event draft = fullEvent(); draft.setStatus("DRAFT");
        assertEquals("/organizer/events/e1/wizard?step=3", ReadinessService.fixLink(draft, ReadinessEngine.Area.COVER_DESCRIPTION));
        assertEquals("/organizer/events/e1/wizard?step=4", ReadinessService.fixLink(draft, ReadinessEngine.Area.PROGRAM));
        Event live = fullEvent();
        assertEquals("/organizer/events/e1/edit", ReadinessService.fixLink(live, ReadinessEngine.Area.BASICS));
        assertEquals("/organizer/events/e1/program", ReadinessService.fixLink(live, ReadinessEngine.Area.TICKETS));
    }
}
