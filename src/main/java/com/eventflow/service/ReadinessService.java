package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.service.ReadinessEngine.Area;
import com.eventflow.service.ReadinessEngine.Inputs;
import com.eventflow.service.ReadinessEngine.Insight;
import com.eventflow.service.ReadinessEngine.Readiness;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads what the Organizer Copilot needs for an event (in parallel) and runs {@link ReadinessEngine}. */
@Service
@RequiredArgsConstructor
public class ReadinessService {
    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();

    private final TicketTypeService ticketTypeService;
    private final EventProgramService eventProgramService;
    private final EventGalleryService eventGalleryService;
    private final RegistrationService registrationService;
    private final CouponService couponService;

    public Inputs inputs(Event event) {
        String id = event.getId();
        var tickets = CompletableFuture.supplyAsync(() -> ticketTypeService.findByEvent(id), IO);
        var sessions = CompletableFuture.supplyAsync(() -> (long) eventProgramService.findSchedule(id).size(), IO);
        var speakers = CompletableFuture.supplyAsync(() -> (long) eventProgramService.findSpeakers(id).size(), IO);
        var sponsors = CompletableFuture.supplyAsync(() -> (long) eventProgramService.findSponsors(id).size(), IO);
        var gallery = CompletableFuture.supplyAsync(() -> eventGalleryService.countByEvent(id), IO);
        var registrations = CompletableFuture.supplyAsync(() -> registrationService.countByEvent(id), IO);
        var coupons = CompletableFuture.supplyAsync(() -> (long) couponService.findByEvent(id).size(), IO);
        return new Inputs(tickets.join(), sessions.join(), speakers.join(), sponsors.join(), gallery.join(),
                registrations.join(), coupons.join(), LocalDate.now());
    }

    public Readiness assess(Event event) { return ReadinessEngine.assess(event, inputs(event)); }

    public List<Insight> insights(Event event) {
        return ReadinessEngine.insights(event, inputs(event), "/organizer/events/" + event.getId());
    }

    /** One result per event, loaded in parallel. */
    public Map<String, Readiness> assessAll(List<Event> events) {
        Map<String, CompletableFuture<Readiness>> futures = new HashMap<>();
        for (Event e : events) futures.put(e.getId(), CompletableFuture.supplyAsync(() -> assess(e), IO));
        Map<String, Readiness> out = new HashMap<>();
        futures.forEach((id, f) -> out.put(id, f.join()));
        return out;
    }

    /** Where to fix a check: the wizard step for drafts, the edit or programme page for published events. */
    public static String fixLink(Event event, Area area) {
        String base = "/organizer/events/" + event.getId();
        boolean draft = EventRules.isEditableDraft(event);
        int step = switch (area) { case BASICS -> 1; case DATE_VENUE -> 2; case COVER_DESCRIPTION -> 3; case PROGRAM -> 4; case TICKETS -> 5; };
        if (draft) return base + "/wizard?step=" + step;
        return switch (area) { case PROGRAM, TICKETS -> base + "/program"; default -> base + "/edit"; };
    }
}
