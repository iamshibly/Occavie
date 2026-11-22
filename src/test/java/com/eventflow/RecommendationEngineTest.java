package com.eventflow;

import com.eventflow.model.Event;
import com.eventflow.service.RecommendationEngine;
import com.eventflow.service.RecommendationEngine.Recommendation;
import com.eventflow.service.RecommendationEngine.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationEngineTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private static Event event(String id, String title, String category, int daysAhead, int price, String venue, String organizer) {
        return Event.builder().id(id).title(title).category(category).location(venue).date(TODAY.plusDays(daysAhead))
                .capacity(100).status("UPCOMING").organizerId(organizer).organizerName("Org " + organizer)
                .pricingType(price > 0 ? "PAID" : "FREE").basePrice(BigDecimal.valueOf(price)).build();
    }

    private final Event savedTech = event("s1", "Flutter Mobile Dev Day", "Technology", -30, 0, "Banani Creative Studio", "o1");
    private final Event bookedMusic = event("s2", "Acoustic Evening", "Music", -10, 300, "Gulshan Hub", "o2");

    private List<Event> pool() {
        return List.of(
                event("a", "Cloud Native Meetup", "Technology", 20, 0, "BICC", "o3"),
                event("b", "Battle of the Bands", "Music", 5, 250, "SEU Stage", "o4"),
                event("c", "Yoga by the Lake", "Health", 3, 0, "Dhanmondi Lake", "o5"),
                event("d", "Resume Clinic", "Career", 40, 0, "SEU Hall", "o6"));
    }

    @Test
    void categoryInterestRanksFirstAndExplainsWhy() {
        var profile = RecommendationEngine.profile(List.of(new Signal(savedTech, "saved")));
        List<Recommendation> recs = RecommendationEngine.rank(pool(), profile, Map.of(), TODAY, 4);
        Recommendation first = recs.get(0);
        assertEquals("Cloud Native Meetup", first.event().getTitle(), "Technology event wins for someone who saved a Technology event");
        assertEquals("Because you saved Flutter Mobile Dev Day", first.topReason());
    }

    @Test
    void bookedOrSavedEventsAreNeverRecommendedAgain() {
        var profile = RecommendationEngine.profile(List.of(new Signal(savedTech, "saved"), new Signal(bookedMusic, "registered")));
        List<Event> candidates = new java.util.ArrayList<>(pool());
        candidates.add(savedTech); candidates.add(bookedMusic);
        List<Recommendation> recs = RecommendationEngine.rank(candidates, profile, Map.of(), TODAY, 10);
        assertTrue(recs.stream().noneMatch(r -> r.event().getId().equals("s1") || r.event().getId().equals("s2")));
        assertTrue(recs.stream().anyMatch(r -> r.topReason().startsWith("Because you registered for Acoustic Evening")), "booking is a stronger, named reason");
    }

    @Test
    void newVisitorsGetTimingAndDemandReasons() {
        var empty = RecommendationEngine.profile(List.of());
        assertFalse(empty.hasHistory());
        List<Recommendation> recs = RecommendationEngine.rank(pool(), empty, Map.of("c", 85L), TODAY, 3);
        assertEquals("Yoga by the Lake", recs.get(0).event().getTitle(), "soon + filling fast + free comes first");
        assertTrue(recs.get(0).reasons().stream().anyMatch(r -> r.startsWith("Filling fast")));
        assertTrue(recs.stream().allMatch(r -> !r.reasons().isEmpty()), "every suggestion has a reason");
    }

    @Test
    void soldOutPastAndCancelledEventsAreSkipped() {
        Event full = event("full", "Sold Out Show", "Music", 4, 0, "X", "o9");
        Event past = event("past", "Old Event", "Music", -2, 0, "X", "o9");
        Event cancelled = event("cx", "Cancelled", "Music", 6, 0, "X", "o9"); cancelled.setStatus("CANCELLED");
        List<Recommendation> recs = RecommendationEngine.rank(List.of(full, past, cancelled), RecommendationEngine.profile(List.of()), Map.of("full", 100L), TODAY, 5);
        assertTrue(recs.isEmpty());
    }

    @Test
    void priceLevelAndAreaAreUsedWhenRelevant() {
        Event paidBooked = event("p1", "Design Jam", "Design", -5, 400, "Banani Creative Studio", "o7");
        var profile = RecommendationEngine.profile(List.of(new Signal(paidBooked, "registered")));
        Event similarPaid = event("n1", "Poster Workshop", "Arts", 30, 450, "Banani Creative Studio", "o8");
        Event expensive = event("n2", "Gala Dinner", "Arts", 30, 3000, "Elsewhere", "o8");
        List<Recommendation> recs = RecommendationEngine.rank(List.of(similarPaid, expensive), profile, Map.of(), TODAY, 2);
        assertEquals("Poster Workshop", recs.get(0).event().getTitle());
        String all = String.join(" | ", recs.get(0).reasons());
        assertTrue(all.contains("Banani") || all.contains("Priced like"), "explains with area or price: " + all);
    }

    @Test
    void organizerReasonIsSuppressedWhenOneOrganizerRunsEverything() {
        Event seen = event("x0", "Seen One", "Music", -3, 0, "V0", "same");
        var profile = RecommendationEngine.profile(List.of(new Signal(seen, "saved")));
        List<Event> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(event("e" + i, "Event " + i, "Other", 10 + i, 0, "V" + i, "same"));
        List<Recommendation> recs = RecommendationEngine.rank(many, profile, Map.of(), TODAY, 5);
        assertTrue(recs.stream().flatMap(r -> r.reasons().stream()).noneMatch(r -> r.startsWith("From Org same")),
                "'same organizer' says nothing when one organizer runs most events");
    }

    @Test
    void listIsDiverseAcrossCategories() {
        var profile = RecommendationEngine.profile(List.of(new Signal(savedTech, "saved")));
        List<Event> many = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) many.add(event("t" + i, "Tech " + i, "Technology", 10 + i, 0, "V" + i, "o" + i));
        many.add(event("m1", "Music Night", "Music", 12, 0, "VM", "om"));
        many.add(event("h1", "Health Day", "Health", 13, 0, "VH", "oh"));
        List<Recommendation> recs = RecommendationEngine.rank(many, profile, Map.of(), TODAY, 4);
        long distinct = recs.stream().map(r -> r.event().getCategory()).distinct().count();
        assertTrue(distinct >= 2, "not four of the same category in a row");
        assertEquals("Technology", recs.get(0).event().getCategory());
    }

    @Test
    void relatedEventsExplainTheLink() {
        Event base = event("base", "AI Summit", "Technology", 10, 0, "BICC, Dhaka", "o1");
        Event sameCategory = event("r1", "Cloud Day", "Technology", 11, 0, "BICC, Dhaka", "o2");
        Event other = event("r2", "Poetry Night", "Arts", 60, 500, "Far Away", "o3");
        List<Recommendation> recs = RecommendationEngine.related(base, List.of(base, sameCategory, other), null, Map.of(), TODAY, 2);
        assertEquals("Cloud Day", recs.get(0).event().getTitle());
        assertTrue(recs.stream().noneMatch(r -> r.event().getId().equals("base")), "never suggests the event itself");
        assertTrue(recs.get(0).reasons().contains("Same category: Technology"));
        assertTrue(recs.get(0).reasons().stream().anyMatch(r -> r.contains("Around the same time") || r.contains("Also free") || r.contains("Also around")));
    }

    @Test
    void cityNamesSharedByManyEventsAreNotUsedAsANearbyReason() {
        Event seen = event("x0", "Seen One", "Music", -3, 0, "BICC, Dhaka", "o0");
        var profile = RecommendationEngine.profile(List.of(new Signal(seen, "saved")));
        List<Event> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(event("e" + i, "Event " + i, "Other", 10 + i, 0, "Venue " + i + ", Dhaka", "o" + (i + 1)));
        List<Recommendation> recs = RecommendationEngine.rank(many, profile, Map.of(), TODAY, 5);
        assertTrue(recs.stream().flatMap(r -> r.reasons().stream()).noneMatch(r -> r.startsWith("Near Dhaka")),
                "'Dhaka' is in nearly every venue, so it is not a useful reason");
    }
}
