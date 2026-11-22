package com.eventflow.service;

import com.eventflow.model.Event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Explainable event recommendations. Pure logic (no database, no randomness, no external service): every point of a score
 * comes with a plain-English reason that is shown to the visitor, e.g. "Because you saved Flutter Mobile Dev Day".
 *
 * Signals: events the participant saved or booked (category, organizer, venue area, price level), plus timing and demand.
 */
public final class RecommendationEngine {
    private RecommendationEngine() { }

    public record Recommendation(Event event, int score, List<String> reasons) {
        public String topReason() { return reasons.isEmpty() ? "" : reasons.get(0); }
    }

    /** One thing the participant did: "saved" or "registered" an event. Most recent first when passed as a list. */
    public record Signal(Event event, String action) { }

    private static final Set<String> GENERIC_PLACE = Set.of("center", "centre", "hall", "auditorium", "lab", "studio", "training",
            "convention", "university", "hub", "building", "room", "seminar", "central", "open", "stage", "ground", "sports",
            "community", "innovation", "creative", "business", "computer", "indoor", "library", "lake", "track", "running", "campus", "the", "and",
            // city names are too broad to mean "nearby"
            "dhaka", "chittagong", "chattogram", "sylhet", "khulna", "rajshahi", "barisal", "rangpur", "mymensingh", "comilla", "bangladesh");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    /** What we know about a participant. Empty for visitors and new accounts. */
    public static final class Profile {
        final Map<String, Double> categoryWeight = new HashMap<>();
        final Map<String, String> categoryReason = new HashMap<>();       // category -> "Because you saved X"
        final Map<String, String> organizerExample = new HashMap<>();     // organizerId -> title
        final Map<String, String> venueExample = new HashMap<>();         // venue token -> title
        final Set<String> excluded = new HashSet<>();
        int freeCount, paidCount;
        BigDecimal paidTotal = BigDecimal.ZERO;

        public boolean hasHistory() { return !categoryWeight.isEmpty(); }
        public Set<String> excludedIds() { return excluded; }
        BigDecimal averagePaid() { return paidCount == 0 ? BigDecimal.ZERO : paidTotal.divide(BigDecimal.valueOf(paidCount), 0, java.math.RoundingMode.HALF_UP); }
    }

    public static Profile profile(List<Signal> signals) {
        Profile p = new Profile();
        if (signals == null) return p;
        Map<String, Integer> strength = new HashMap<>();                   // category -> strongest action seen (registered=2, saved=1)
        for (Signal s : signals) {
            Event e = s.event();
            if (e == null) continue;
            boolean registered = "registered".equals(s.action());
            p.excluded.add(e.getId());
            String category = e.getCategory();
            if (category != null && !category.isBlank()) {
                p.categoryWeight.merge(category, registered ? 2.0 : 1.0, Double::sum);
                int level = registered ? 2 : 1;
                if (!p.categoryReason.containsKey(category) || level > strength.getOrDefault(category, 0)) {
                    strength.put(category, level);
                    p.categoryReason.put(category, (registered ? "Because you registered for " : "Because you saved ") + e.getTitle());
                }
            }
            if (e.getOrganizerId() != null) p.organizerExample.putIfAbsent(e.getOrganizerId(), e.getTitle());
            for (String token : placeTokens(e.getLocation())) p.venueExample.putIfAbsent(token, e.getTitle());
            if (EventRules.isPaid(e) && EventRules.basePrice(e).signum() > 0) { p.paidCount++; p.paidTotal = p.paidTotal.add(EventRules.basePrice(e)); }
            else p.freeCount++;
        }
        return p;
    }

    /** "Recommended for you": best upcoming events the participant has not booked or saved. */
    public static List<Recommendation> rank(List<Event> candidates, Profile profile, Map<String, Long> booked, LocalDate today, int limit) {
        List<Event> pool = eligible(candidates, profile, booked, today);
        Map<String, Integer> organizerShare = organizerCounts(pool);
        Set<String> common = commonTokens(pool);
        List<Recommendation> scored = new ArrayList<>();
        for (Event c : pool) scored.add(score(c, profile, booked, today, organizerShare, pool.size(), common));
        return diversify(scored, limit);
    }

    /** "You might also like" on an event page: similar to this event, nudged by what the viewer likes. */
    public static List<Recommendation> related(Event base, List<Event> candidates, Profile profile, Map<String, Long> booked, LocalDate today, int limit) {
        Profile p = profile == null ? new Profile() : profile;
        List<Event> pool = eligible(candidates, p, booked, today);
        pool.removeIf(e -> base.getId() != null && base.getId().equals(e.getId()));
        Map<String, Integer> organizerShare = organizerCounts(pool);
        Set<String> common = commonTokens(pool);
        Set<String> baseTokens = placeTokens(base.getLocation());
        List<Recommendation> scored = new ArrayList<>();
        for (Event c : pool) {
            List<Weighted> why = new ArrayList<>();
            if (base.getCategory() != null && base.getCategory().equalsIgnoreCase(c.getCategory())) why.add(new Weighted(30, "Same category: " + c.getCategory()));
            Set<String> shared = new HashSet<>(placeTokens(c.getLocation())); shared.retainAll(baseTokens); shared.removeAll(common);
            if (!shared.isEmpty()) why.add(new Weighted(12, "Also around " + capitalize(shared.iterator().next())));
            if (c.getOrganizerId() != null && c.getOrganizerId().equals(base.getOrganizerId()) && informative(c, organizerShare, pool.size()))
                why.add(new Weighted(10, "Same organizer: " + c.getOrganizerName()));
            if (base.getDate() != null && c.getDate() != null && Math.abs(ChronoUnit.DAYS.between(base.getDate(), c.getDate())) <= 3)
                why.add(new Weighted(8, "Around the same time (" + c.getDate().format(DAY) + ")"));
            boolean baseFree = !EventRules.isPaid(base) || EventRules.basePrice(base).signum() <= 0;
            boolean candFree = !EventRules.isPaid(c) || EventRules.basePrice(c).signum() <= 0;
            if (baseFree && candFree) why.add(new Weighted(6, "Also free"));
            else if (!baseFree && !candFree && within(EventRules.basePrice(base), EventRules.basePrice(c), 0.5)) why.add(new Weighted(6, "Similar price"));
            Double w = p.categoryWeight.get(c.getCategory());
            if (w != null) why.add(new Weighted(Math.min(25, 8 + (int) (6 * w)), p.categoryReason.get(c.getCategory())));
            why.addAll(demand(c, booked, today));
            scored.add(build(c, why));
        }
        return diversify(scored, limit);
    }

    // ------------------------------------------------------------------ scoring

    private record Weighted(int weight, String text) { }

    private static Recommendation score(Event c, Profile p, Map<String, Long> booked, LocalDate today, Map<String, Integer> organizerShare, int poolSize, Set<String> common) {
        List<Weighted> why = new ArrayList<>();
        Double w = p.categoryWeight.get(c.getCategory());
        if (w != null) why.add(new Weighted(Math.min(45, 15 + (int) (10 * w)), p.categoryReason.get(c.getCategory())));
        if (c.getOrganizerId() != null && p.organizerExample.containsKey(c.getOrganizerId()) && informative(c, organizerShare, poolSize))
            why.add(new Weighted(14, "From " + c.getOrganizerName() + ", who also runs " + p.organizerExample.get(c.getOrganizerId())));
        for (String token : placeTokens(c.getLocation())) {
            if (!common.contains(token) && p.venueExample.containsKey(token)) { why.add(new Weighted(10, "Near " + capitalize(token) + ", like " + p.venueExample.get(token))); break; }
        }
        boolean free = !EventRules.isPaid(c) || EventRules.basePrice(c).signum() <= 0;
        if (p.hasHistory()) {
            if (free && p.freeCount > 0 && p.paidCount == 0) why.add(new Weighted(8, "Free, like the events you picked"));
            else if (!free && p.paidCount > 0 && within(p.averagePaid(), EventRules.basePrice(c), 0.5))
                why.add(new Weighted(8, "Priced like events you booked (about ৳" + p.averagePaid().toPlainString() + ")"));
            else if (free) why.add(new Weighted(3, "Free to join"));
        } else if (free) {
            why.add(new Weighted(3, "Free to join"));
        }
        why.addAll(demand(c, booked, today));
        if (c.isFeatured()) why.add(new Weighted(3, "Featured event"));
        return build(c, why);
    }

    /** Timing and demand signals, shared by both modes. */
    private static List<Weighted> demand(Event c, Map<String, Long> booked, LocalDate today) {
        List<Weighted> out = new ArrayList<>();
        if (c.getDate() != null) {
            long days = ChronoUnit.DAYS.between(today, c.getDate());
            if (days == 0) out.add(new Weighted(10, "Happening today"));
            else if (days == 1) out.add(new Weighted(10, "Tomorrow, " + c.getDate().format(DAY)));
            else if (days <= 7) out.add(new Weighted(9, "This week: " + c.getDate().format(DAY)));
            else if (days <= 14) out.add(new Weighted(4, "Coming up: " + c.getDate().format(DAY)));
        }
        long taken = booked.getOrDefault(c.getId(), 0L);
        int cap = c.getCapacity();
        if (cap > 0) {
            long left = Math.max(0, cap - taken);
            int percent = (int) Math.round(taken * 100.0 / cap);
            if (left > 0 && left <= 10) out.add(new Weighted(9, "Only " + left + (left == 1 ? " seat" : " seats") + " left"));
            else if (percent >= 70) out.add(new Weighted(8, "Filling fast: " + percent + "% booked"));
            else if (taken >= 20) out.add(new Weighted(4, "Popular: " + taken + " people registered"));
        }
        return out;
    }

    private static Recommendation build(Event c, List<Weighted> why) {
        why.sort(Comparator.comparingInt(Weighted::weight).reversed());
        int total = why.stream().mapToInt(Weighted::weight).sum();
        List<String> reasons = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Weighted wgt : why) { if (wgt.text() != null && seen.add(wgt.text()) && reasons.size() < 3) reasons.add(wgt.text()); }
        return new Recommendation(c, total, reasons);
    }

    /**
     * Highest scores first, but no category may take more than half of the list (at least two): a strong interest in one
     * topic should not hide everything else. If there are not enough other events, the remaining slots are filled anyway.
     */
    private static List<Recommendation> diversify(List<Recommendation> scored, int limit) {
        List<Recommendation> ordered = new ArrayList<>(scored);
        ordered.sort(Comparator.comparingInt(Recommendation::score).reversed()
                .thenComparing(r -> r.event().getDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> String.valueOf(r.event().getTitle())));
        int cap = Math.max(2, (limit + 1) / 2);
        List<Recommendation> picked = new ArrayList<>();
        Map<String, Integer> perCategory = new HashMap<>();
        for (Recommendation r : ordered) {
            if (picked.size() >= limit) break;
            String category = String.valueOf(r.event().getCategory());
            if (perCategory.getOrDefault(category, 0) >= cap) continue;
            picked.add(r); perCategory.merge(category, 1, Integer::sum);
        }
        for (Recommendation r : ordered) {                         // not enough variety available: fill the rest
            if (picked.size() >= limit) break;
            if (!picked.contains(r)) picked.add(r);
        }
        return picked;
    }

    // ------------------------------------------------------------------ helpers

    private static List<Event> eligible(Collection<Event> candidates, Profile p, Map<String, Long> booked, LocalDate today) {
        List<Event> out = new ArrayList<>();
        for (Event c : candidates == null ? List.<Event>of() : candidates) {
            if (c == null || c.getId() == null || p.excluded.contains(c.getId())) continue;
            if (!EventRules.isPublished(c) || EventRules.isCancelled(c) || c.getDate() == null || c.getDate().isBefore(today)) continue;
            if (c.getCapacity() > 0 && booked.getOrDefault(c.getId(), 0L) >= c.getCapacity()) continue;        // sold out
            out.add(c);
        }
        return out;
    }

    private static Map<String, Integer> organizerCounts(List<Event> pool) {
        Map<String, Integer> counts = new HashMap<>();
        for (Event e : pool) if (e.getOrganizerId() != null) counts.merge(e.getOrganizerId(), 1, Integer::sum);
        return counts;
    }

    /** When one organizer runs most events, "same organizer" says nothing, so it is not used as a reason. */
    private static boolean informative(Event c, Map<String, Integer> organizerShare, int poolSize) {
        return poolSize == 0 || organizerShare.getOrDefault(c.getOrganizerId(), 0) <= Math.max(3, poolSize * 0.4);
    }

    /** Venue words that appear in many events (such as the city) say nothing about "nearby", so they are not used as a reason. */
    private static Set<String> commonTokens(List<Event> pool) {
        Map<String, Integer> freq = new HashMap<>();
        for (Event e : pool) for (String t : placeTokens(e.getLocation())) freq.merge(t, 1, Integer::sum);
        int limit = Math.max(2, (int) (pool.size() * 0.25));
        Set<String> common = new HashSet<>();
        freq.forEach((t, n) -> { if (n > limit) common.add(t); });
        return common;
    }

    private static boolean within(BigDecimal a, BigDecimal b, double tolerance) {
        if (a.signum() <= 0 || b.signum() <= 0) return false;
        double ratio = b.doubleValue() / a.doubleValue();
        return ratio >= 1 - tolerance && ratio <= 1 + tolerance;
    }

    static Set<String> placeTokens(String location) {
        Set<String> tokens = new HashSet<>();
        if (location == null) return tokens;
        for (String t : location.toLowerCase(Locale.ROOT).split("[,\\s]+")) if (t.length() >= 4 && !GENERIC_PLACE.contains(t)) tokens.add(t);
        return tokens;
    }

    private static String capitalize(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
