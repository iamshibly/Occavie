package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.model.EventSpeaker;
import com.eventflow.model.EventTicketType;
import com.eventflow.model.User;
import com.eventflow.service.EventProgramService;
import com.eventflow.service.EventRules;
import com.eventflow.service.EventService;
import com.eventflow.service.FeedbackService;
import com.eventflow.service.RegistrationService;
import com.eventflow.service.TicketTypeService;
import com.eventflow.service.UserService;
import com.eventflow.view.ViewFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Side-by-side comparison of two or three published events (public page, read-only). */
@Controller
@RequiredArgsConstructor
public class CompareController {
    static final int MAX_EVENTS = 3;

    private final EventService eventService;
    private final TicketTypeService ticketTypeService;
    private final EventProgramService eventProgramService;
    private final RegistrationService registrationService;
    private final FeedbackService feedbackService;
    private final UserService userService;
    private final ViewFormatter display;

    public record Ticket(String name, String price, long left) { }

    public record Item(Event event, String image, String date, String time, String price, BigDecimal priceValue, boolean paid,
                       List<Ticket> tickets, long seatsLeft, int seatPercentLeft, List<String> speakers, int sessions,
                       double rating, long reviews, boolean bookable, boolean past) { }

    @GetMapping("/compare")
    public String compare(@RequestParam(name = "ids", required = false) List<String> rawIds,
                          Authentication authentication, Model model) {
        User user = authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)
                ? userService.currentUser(authentication) : null;
        model.addAttribute("user", user);
        model.addAttribute("isGuest", user == null);

        Set<String> ids = new LinkedHashSet<>();
        if (rawIds != null) {
            for (String chunk : rawIds) {
                for (String id : chunk.split(",")) {
                    String clean = id.trim();
                    if (clean.matches("[A-Za-z0-9_-]{1,40}")) ids.add(clean);
                    if (ids.size() >= MAX_EVENTS) break;
                }
            }
        }
        List<Item> items = new ArrayList<>();
        for (String id : ids) {
            Optional<Event> found = eventService.findOptional(id).filter(EventRules::isPublished);
            found.ifPresent(event -> items.add(build(event)));
        }
        model.addAttribute("items", items);
        Map<String, String> removeLinks = new java.util.HashMap<>();
        for (Item item : items) {
            String rest = items.stream().filter(o -> o != item).map(o -> o.event().getId()).collect(java.util.stream.Collectors.joining(","));
            removeLinks.put(item.event().getId(), rest.isEmpty() ? "/events" : "/compare?ids=" + rest);
        }
        model.addAttribute("removeLinks", removeLinks);
        model.addAttribute("enough", items.size() >= 2);
        if (items.size() >= 2) {
            model.addAttribute("cheapestId", best(items, java.util.Comparator.comparing(Item::priceValue), Item::priceValue));
            model.addAttribute("soonestId", best(items, java.util.Comparator.comparing(i -> i.event().getDate(), java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())),
                    i -> i.event().getDate()));
            model.addAttribute("mostSeatsId", items.stream().max(java.util.Comparator.comparingLong(Item::seatsLeft))
                    .filter(i -> items.stream().anyMatch(o -> o != i && o.seatsLeft() != i.seatsLeft())).map(i -> i.event().getId()).orElse(""));
        }
        model.addAttribute("ogTitle", "Compare events | Occavie");
        return "compare";
    }

    /** Id of the item that is strictly better than the others on the given key, or "" when they tie. */
    private static <K> String best(List<Item> items, java.util.Comparator<Item> order, java.util.function.Function<Item, K> key) {
        Item min = items.stream().min(order).orElse(null);
        if (min == null) return "";
        boolean tie = items.stream().anyMatch(o -> o != min && java.util.Objects.equals(key.apply(o), key.apply(min)));
        return tie ? "" : min.event().getId();
    }

    private Item build(Event event) {
        String id = event.getId();
        List<EventTicketType> all = ticketTypeService.findByEvent(id);
        List<EventTicketType> active = all.stream().filter(EventTicketType::isActive).toList();
        Map<String, Long> sold = ticketTypeService.registrationCounts(id);
        List<Ticket> tickets = active.stream()
                .map(t -> new Ticket(t.getName(), display.ticketPrice(t), Math.max(0, t.getCapacity() - sold.getOrDefault(t.getId(), 0L))))
                .toList();
        BigDecimal value = active.stream().map(EventTicketType::getPrice).filter(java.util.Objects::nonNull).min(java.util.Comparator.naturalOrder())
                .orElse(EventRules.isPaid(event) ? EventRules.basePrice(event) : BigDecimal.ZERO);
        long booked = registrationService.countByEvent(id);
        long left = Math.max(0, event.getCapacity() - booked);
        int percent = event.getCapacity() <= 0 ? 0 : (int) Math.round(left * 100.0 / event.getCapacity());
        List<String> speakers = eventProgramService.findSpeakers(id).stream().map(EventSpeaker::getName).limit(4).toList();
        var feedback = feedbackService.summaryOf(feedbackService.findForEvents(List.of(event)));
        return new Item(event, display.listingImageUrl(event), display.eventDate(event), display.eventTime(event),
                ticketTypeService.startingPriceLabel(event, all), value, value.signum() > 0, tickets, left, percent, speakers,
                eventProgramService.findSchedule(id).size(), feedback.averageRating(), feedback.totalReviews(),
                EventRules.bookable(event) && left > 0, !eventService.isUpcoming(event));
    }
}
