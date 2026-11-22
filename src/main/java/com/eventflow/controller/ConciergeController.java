package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.model.EventTicketType;
import com.eventflow.service.EventRules;
import com.eventflow.service.EventService;
import com.eventflow.service.RegistrationService;
import com.eventflow.service.TicketTypeService;
import com.eventflow.view.ViewFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Public, read-only snapshot of upcoming published events for the Occavie Concierge assistant. The assistant runs in the
 * browser and answers from this data, so it only ever says what the site itself shows (titles, dates, prices, seats left).
 */
@RestController
@RequiredArgsConstructor
public class ConciergeController {
    private static final int MAX_EVENTS = 300;

    private final EventService eventService;
    private final RegistrationService registrationService;
    private final TicketTypeService ticketTypeService;
    private final ViewFormatter display;

    public record Ticket(String n, String p) { }

    public record Item(String id, String t, String c, String d, String tm, String v, String pl, long p, int cap, long left,
                       String img, String org, boolean f, List<Ticket> tk) { }

    @GetMapping("/concierge/data.json")
    public ResponseEntity<Map<String, Object>> data() {
        LocalDate today = LocalDate.now();
        List<Event> events = eventService.findAllSorted().stream()
                .filter(EventRules::isPublished).filter(e -> !EventRules.isCancelled(e))
                .filter(e -> e.getDate() != null && !e.getDate().isBefore(today))
                .sorted(Comparator.comparing(Event::getDate)).limit(MAX_EVENTS).toList();
        Map<String, Long> booked = registrationService.countsForEvents(events.stream().map(Event::getId).toList());
        Map<String, List<EventTicketType>> tickets = ticketTypeService.byEvents(events);
        List<Item> items = events.stream().map(e -> {
            List<EventTicketType> all = tickets.getOrDefault(e.getId(), List.of());
            List<EventTicketType> active = all.stream().filter(EventTicketType::isActive).toList();
            BigDecimal min = active.stream().map(EventTicketType::getPrice).filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(EventRules.isPaid(e) ? EventRules.basePrice(e) : BigDecimal.ZERO);
            long taken = booked.getOrDefault(e.getId(), 0L);
            List<Ticket> tk = active.stream().map(t -> new Ticket(t.getName(), display.ticketPrice(t))).toList();
            return new Item(e.getId(), e.getTitle(), e.getCategory(), e.getDate().toString(), display.eventTime(e), e.getLocation(),
                    ticketTypeService.startingPriceLabel(e, all), min.longValue(), e.getCapacity(), Math.max(0, e.getCapacity() - taken),
                    display.listingImageUrl(e), e.getOrganizerName(), e.isFeatured(), tk);
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(Map.of("today", today.toString(), "events", items));
    }
}
