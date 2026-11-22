package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.model.EventTicketType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Organizer Copilot: scores how complete and attractive an event is (0-100) and says what to improve next.
 * Pure rules over the event and its related records: no database access, no AI service, same input gives the same advice.
 */
public final class ReadinessEngine {
    private ReadinessEngine() { }

    public enum Status { DONE, IMPROVE, MISSING }

    /** Where in the organizer screens a check can be fixed. */
    public enum Area { BASICS, DATE_VENUE, COVER_DESCRIPTION, PROGRAM, TICKETS }

    public record Check(String id, String label, int max, int points, Status status, String tip, Area area) {
        public boolean done() { return status == Status.DONE; }
        public int missing() { return max - points; }
    }

    public record Insight(String icon, String text, String link) { }

    public record Readiness(int score, String level, String tone, List<Check> checks, List<Check> topTips) {
        public boolean ready() { return score >= 75; }
    }

    /** Related records the checks need. Counts only, so callers can load them in bulk. */
    public record Inputs(List<EventTicketType> ticketTypes, long sessions, long speakers, long sponsors, long gallery,
                         long registrations, long coupons, LocalDate today) {
        public static Inputs empty(LocalDate today) { return new Inputs(List.of(), 0, 0, 0, 0, 0, 0, today); }
    }

    public static Readiness assess(Event event, Inputs in) {
        List<Check> checks = new ArrayList<>();
        checks.add(title(event));
        checks.add(description(event));
        checks.add(cover(event));
        checks.add(dateTime(event, in.today()));
        checks.add(venue(event));
        checks.add(schedule(in.sessions()));
        checks.add(speakers(in.speakers()));
        checks.add(tickets(event, in.ticketTypes()));
        checks.add(capacity(event, in.ticketTypes()));
        checks.addAll(extras(in.gallery(), in.sponsors()));
        int score = checks.stream().mapToInt(Check::points).sum();
        List<Check> top = checks.stream().filter(c -> !c.done()).sorted(Comparator.comparingInt(Check::missing).reversed()).limit(3).toList();
        return new Readiness(score, level(score), tone(score), List.copyOf(checks), top);
    }

    /** Growth tips for events that are already published, based on bookings and the calendar. */
    public static List<Insight> insights(Event event, Inputs in, String basePath) {
        List<Insight> out = new ArrayList<>();
        if (event.getDate() == null || event.getCapacity() <= 0) return out;
        long days = ChronoUnit.DAYS.between(in.today(), event.getDate());
        if (days < 0) return out;
        int percent = (int) Math.round(in.registrations() * 100.0 / event.getCapacity());
        if (days <= 14 && percent < 30) {
            String state = in.registrations() == 0 ? "No bookings yet" : "Only " + percent + "% booked";
            out.add(new Insight("bi-megaphone", state + " with " + days + (days == 1 ? " day" : " days")
                    + " to go. Send an announcement and share the event link.", basePath + "/announcements"));
        }
        boolean paid = EventRules.isPaid(event) && EventRules.basePrice(event).signum() > 0;
        if (paid && in.coupons() == 0 && percent < 60) {
            out.add(new Insight("bi-percent", "Add an early-bird or first-timer coupon to lift bookings.", "/organizer/coupons"));
        }
        if (percent >= 90) {
            out.add(new Insight("bi-hourglass-split", "Nearly full (" + percent + "%). Check the waitlist or raise capacity if the venue allows.", basePath + "/waitlist"));
        } else if (percent >= 70 && days > 3) {
            out.add(new Insight("bi-graph-up-arrow", "Strong demand: " + percent + "% booked. Consider adding a VIP or extra ticket type.", basePath + "/program"));
        }
        if (in.registrations() == 0 && days > 14) {
            out.add(new Insight("bi-share", "No bookings yet. Share the link and add a few details so the page converts better.", basePath + "/program"));
        }
        return out;
    }

    // ------------------------------------------------------------------ checks

    private static Check title(Event e) {
        String t = e.getTitle() == null ? "" : e.getTitle().trim();
        if (t.isEmpty()) return new Check("title", "Title", 6, 0, Status.MISSING, "Give the event a clear title.", Area.BASICS);
        long letters = t.chars().filter(Character::isLetter).count();
        boolean shouting = letters >= 6 && t.equals(t.toUpperCase());
        if (t.length() < 12) return new Check("title", "Title", 6, 3, Status.IMPROVE, "Make the title more specific (12 to 70 characters): say what it is and who it is for.", Area.BASICS);
        if (t.length() > 80) return new Check("title", "Title", 6, 3, Status.IMPROVE, "Shorten the title to under 70 characters so it is not cut off on cards.", Area.BASICS);
        if (shouting) return new Check("title", "Title", 6, 3, Status.IMPROVE, "Avoid ALL CAPS in the title; it reads as shouting.", Area.BASICS);
        return new Check("title", "Title", 6, 6, Status.DONE, "", Area.BASICS);
    }

    private static Check description(Event e) {
        String d = e.getDescription() == null ? "" : e.getDescription().trim().replaceAll("\\s+", " ");
        if (d.length() < 40) return new Check("description", "Description", 16, 0, Status.MISSING,
                "Add a description: what will people learn or experience, who is it for, and what should they bring?", Area.COVER_DESCRIPTION);
        if (d.length() < 120) return new Check("description", "Description", 16, 8, Status.IMPROVE,
                "Expand the description to at least 120 characters: add the benefit for attendees and who it is for.", Area.COVER_DESCRIPTION);
        return new Check("description", "Description", 16, 16, Status.DONE, "", Area.COVER_DESCRIPTION);
    }

    private static Check cover(Event e) {
        String img = e.getImageUrl();
        boolean real = img != null && !img.isBlank() && !"/images/event-visual.png".equals(img);
        return real ? new Check("cover", "Cover photo", 14, 14, Status.DONE, "", Area.COVER_DESCRIPTION)
                : new Check("cover", "Cover photo", 14, 0, Status.MISSING, "Add a real cover photo: events with a photo get noticed far more in the list.", Area.COVER_DESCRIPTION);
    }

    private static Check dateTime(Event e, LocalDate today) {
        if (e.getDate() == null) return new Check("date", "Date and time", 8, 0, Status.MISSING, "Set the event date.", Area.DATE_VENUE);
        if (e.getTime() == null) return new Check("date", "Date and time", 8, 4, Status.IMPROVE, "Add a start time so people can plan their day.", Area.DATE_VENUE);
        if (e.getDate().isBefore(today) && !"COMPLETED".equals(e.getStatus()) && !"CANCELLED".equals(e.getStatus()))
            return new Check("date", "Date and time", 8, 4, Status.IMPROVE, "The date is in the past: choose a new date before publishing.", Area.DATE_VENUE);
        return new Check("date", "Date and time", 8, 8, Status.DONE, "", Area.DATE_VENUE);
    }

    private static Check venue(Event e) {
        boolean location = e.getLocation() != null && !e.getLocation().isBlank();
        boolean address = e.getAddress() != null && !e.getAddress().isBlank();
        boolean online = e.getOnlineUrl() != null && !e.getOnlineUrl().isBlank();
        if (!location && !online) return new Check("venue", "Venue", 8, 0, Status.MISSING, "Add the venue name, or an online link for a virtual event.", Area.DATE_VENUE);
        if (!address && !online) return new Check("venue", "Venue", 8, 4, Status.IMPROVE, "Add the full address so attendees can find you (it also powers the map link).", Area.DATE_VENUE);
        return new Check("venue", "Venue", 8, 8, Status.DONE, "", Area.DATE_VENUE);
    }

    private static Check schedule(long sessions) {
        if (sessions == 0) return new Check("schedule", "Schedule", 12, 0, Status.MISSING, "Add a schedule: people book more when they can see the plan for the day.", Area.PROGRAM);
        if (sessions < 3) return new Check("schedule", "Schedule", 12, 6, Status.IMPROVE, "Add at least 3 sessions (for example welcome, main session, questions and networking).", Area.PROGRAM);
        return new Check("schedule", "Schedule", 12, 12, Status.DONE, "", Area.PROGRAM);
    }

    private static Check speakers(long speakers) {
        return speakers > 0 ? new Check("speakers", "Speakers or hosts", 10, 10, Status.DONE, "", Area.PROGRAM)
                : new Check("speakers", "Speakers or hosts", 10, 0, Status.MISSING, "Add at least one speaker or host so attendees know who they will hear from.", Area.PROGRAM);
    }

    private static Check tickets(Event e, List<EventTicketType> types) {
        List<EventTicketType> active = types.stream().filter(EventTicketType::isActive).toList();
        boolean paid = EventRules.isPaid(e) && EventRules.basePrice(e).signum() > 0;
        if (active.isEmpty()) {
            return paid ? new Check("tickets", "Tickets", 12, 0, Status.MISSING, "Add ticket types with prices (for example Standard and VIP) so buyers can choose.", Area.TICKETS)
                    : new Check("tickets", "Tickets", 12, 6, Status.IMPROVE, "Add a General Admission ticket with a seat limit so free seats are tracked.", Area.TICKETS);
        }
        if (paid && active.stream().anyMatch(t -> t.getPrice() == null || t.getPrice().signum() <= 0))
            return new Check("tickets", "Tickets", 12, 6, Status.IMPROVE, "Some ticket types on a paid event are free or have no price: check the prices.", Area.TICKETS);
        return new Check("tickets", "Tickets", 12, 12, Status.DONE, "", Area.TICKETS);
    }

    private static Check capacity(Event e, List<EventTicketType> types) {
        if (e.getCapacity() <= 0) return new Check("capacity", "Capacity", 6, 0, Status.MISSING, "Set how many people can attend.", Area.BASICS);
        int sum = types.stream().filter(EventTicketType::isActive).mapToInt(EventTicketType::getCapacity).sum();
        if (sum > 0 && sum != e.getCapacity())
            return new Check("capacity", "Capacity", 6, 3, Status.IMPROVE, "Ticket seats add up to " + sum + " but the event capacity is " + e.getCapacity()
                    + ". Make them match so no seats are lost or oversold.", Area.TICKETS);
        return new Check("capacity", "Capacity", 6, 6, Status.DONE, "", Area.BASICS);
    }

    private static List<Check> extras(long gallery, long sponsors) {
        return List.of(
                gallery > 0 ? new Check("gallery", "Photo gallery", 4, 4, Status.DONE, "", Area.PROGRAM)
                        : new Check("gallery", "Photo gallery", 4, 0, Status.MISSING, "Add a few photos (venue, past editions) to build trust.", Area.PROGRAM),
                sponsors > 0 ? new Check("sponsors", "Sponsors or partners", 4, 4, Status.DONE, "", Area.PROGRAM)
                        : new Check("sponsors", "Sponsors or partners", 4, 0, Status.MISSING, "Add sponsors or partners if you have them; it makes the event look established.", Area.PROGRAM));
    }

    static String level(int score) {
        if (score >= 90) return "Excellent";
        if (score >= 75) return "Ready to publish";
        if (score >= 50) return "Almost there";
        return "Needs work";
    }

    static String tone(int score) {
        if (score >= 75) return "good";
        if (score >= 50) return "okay";
        return "low";
    }

    static BigDecimal zero() { return BigDecimal.ZERO; }
}
