package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.dto.HomeEventCard;
import com.eventflow.view.ViewFormatter;
import com.eventflow.model.Registration;
import com.eventflow.model.EventSpeaker;
import com.eventflow.model.User;
import com.eventflow.service.EventService;
import com.eventflow.service.EventProgramService;
import com.eventflow.service.FeedbackService;
import com.eventflow.service.RegistrationService;
import com.eventflow.service.UserService;
import com.eventflow.service.TicketTypeService;
import com.eventflow.service.EventGalleryService;
import com.eventflow.service.AnalyticsService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequiredArgsConstructor
public class MainController {

    private final UserService userService;
    private final EventService eventService;
    private final RegistrationService registrationService;
    private final FeedbackService feedbackService;
    private final EventProgramService eventProgramService;
    private final TicketTypeService ticketTypeService;
    private final EventGalleryService eventGalleryService;
    private final AnalyticsService analyticsService;
    private final ViewFormatter display;
    private final com.eventflow.service.NotificationService notificationService;
    private final com.eventflow.service.WaitlistService waitlistService;
    private final com.eventflow.service.SavedEventService savedEventService;
    private final com.eventflow.service.EventSuggestionService suggestionService;
    private final com.eventflow.service.OrganizerProfileService organizerProfileService;
    private final com.eventflow.service.ReadinessService readinessService;



    @GetMapping({"/", "/home"})
    public String home(Authentication authentication, Model model) {
        User user = currentUserOrNull(authentication);
        List<Event> events = eventService.findAllSorted().stream().filter(com.eventflow.service.EventRules::isPublished).toList();
        List<Event> upcomingEvents = events.stream()
                .filter(eventService::isUpcoming)
                .limit(6)
                .toList();

        List<Event> allUpcoming = events.stream().filter(eventService::isUpcoming).toList();
        model.addAttribute("upcomingCount", allUpcoming.size());
        model.addAttribute("user", user);
        model.addAttribute("isGuest", user == null);
        model.addAttribute("featuredEvents", upcomingEvents.stream()
                .map(event -> HomeEventCard.from(event, display)).toList());
        model.addAttribute("registrationCounts", eventService.registrationCounts(upcomingEvents));
        model.addAttribute("startingPrices", startingPriceLabels(upcomingEvents));
        // Category chips come from real, upcoming events only.
        model.addAttribute("categories", events.stream().filter(eventService::isUpcoming)
                .map(Event::getCategory).filter(c -> c != null && !c.isBlank()).distinct().sorted().toList());
        return "home";
    }

    @GetMapping("/events")
    public String events(Authentication authentication, Model model) {
        User user = currentUserOrNull(authentication);
        List<Event> events = eventService.findAllSorted().stream().filter(com.eventflow.service.EventRules::isPublished).toList();
        // Event discovery is shared by every role; organizer-owned events stay visible.
        model.addAttribute("user", user);
        model.addAttribute("isGuest", user == null);
        model.addAttribute("events", events);
        model.addAttribute("initialEventCount", user == null
                ? events.stream().filter(eventService::isUpcoming).count()
                : events.size());
        model.addAttribute("categories", events.stream().map(Event::getCategory).distinct().sorted().toList());
        model.addAttribute("registrationCounts", eventService.registrationCounts(events));
        model.addAttribute("feedbackSummaries", feedbackService.summariesForEvents(events));
        Map<String, List<com.eventflow.model.EventTicketType>> ticketTypes = ticketTypeService.byEvents(events);
        model.addAttribute("ticketTypesByEvent", ticketTypes);
        model.addAttribute("startingPrices", startingPriceLabels(events, ticketTypes));

        if (user != null && "PARTICIPANT".equals(user.getRole())) {
            Set<String> registeredEventIds = new HashSet<>();
            registrationService.findByParticipant(user.getId())
                    .forEach(registration -> registeredEventIds.add(registration.getEventId()));
            model.addAttribute("registeredEventIds", registeredEventIds);
        }
        return "events";
    }


    @GetMapping("/events/{id}")
    public String eventDetails(@PathVariable String id, Authentication authentication, Model model) {
        User user = currentUserOrNull(authentication);
        Event event = eventService.findOptional(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Event not found"));
        // Drafts are private: only the owning organizer and admins may preview them.
        boolean draft = !com.eventflow.service.EventRules.isPublished(event);
        if (draft && !(user != null && ("ADMIN".equals(user.getRole())
                || ("ORGANIZER".equals(user.getRole()) && user.getId().equals(event.getOrganizerId()))))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Event not found");
        }
        model.addAttribute("isDraftPreview", draft);
        // Independent database reads run in parallel (each is a network round trip to MongoDB), so the page costs roughly
        // the slowest query instead of the sum of all of them.
        String viewerId = user == null ? null : user.getId();
        boolean participant = user != null && "PARTICIPANT".equals(user.getRole());
        var fSchedule = async(() -> eventProgramService.findSchedule(id));
        var fSpeakers = async(() -> eventProgramService.findSpeakers(id));
        var fSponsors = async(() -> eventProgramService.findSponsors(id));
        var fTickets = async(() -> ticketTypeService.findByEvent(id));
        var fTicketCounts = async(() -> ticketTypeService.registrationCounts(id));
        var fFeedback = async(() -> feedbackService.findForEvents(List.of(event)));
        var fRegCount = async(() -> registrationService.countByEvent(id));
        var fGallery = async(() -> eventGalleryService.findByEvent(id));
        var fRegistered = async(() -> participant && registrationService.isRegistered(id, viewerId));
        var fSaved = async(() -> savedEventService.isSaved(user, id));
        var fOrganizerPublic = async(() -> organizerProfileService.hasPublicPage(event.getOrganizerId()));
        var fRelated = async(() -> draft ? List.<com.eventflow.service.RecommendationEngine.Recommendation>of() : suggestionService.relatedTo(event, user, 3));
        var fRoom = async(() -> waitlistService.hasRoom(event, viewerId));
        var fEntry = async(() -> participant
                ? waitlistService.entryFor(id, viewerId).filter(com.eventflow.model.WaitlistEntry::isActive).orElse(null) : null);
        var fOffer = async(() -> waitlistService.validOffer(id, viewerId).orElse(null));
        var fWaiting = async(() -> waitlistService.waitingInOrder(id).size());

        var schedule = await(fSchedule);
        List<EventSpeaker> speakers = await(fSpeakers);
        var sponsors = await(fSponsors);
        var allTicketTypes = await(fTickets);
        var ticketTypes = allTicketTypes.stream().filter(com.eventflow.model.EventTicketType::isActive).toList();
        var feedback = await(fFeedback);

        model.addAttribute("user", user);
        model.addAttribute("isGuest", user == null);
        model.addAttribute("event", event);
        model.addAttribute("schedule", schedule);
        model.addAttribute("speakers", speakers);
        model.addAttribute("sponsors", sponsors);
        model.addAttribute("ticketTypes", ticketTypes);
        model.addAttribute("hasCustomTicketTypes", !allTicketTypes.isEmpty());
        model.addAttribute("ticketRegistrationCounts", await(fTicketCounts));
        model.addAttribute("startingPrice", ticketTypeService.startingPriceLabel(event, allTicketTypes));
        model.addAttribute("speakerMap", eventProgramService.speakerMap(speakers));
        model.addAttribute("registrationCount", await(fRegCount));
        model.addAttribute("feedbackSummary", feedbackService.summaryOf(feedback));
        model.addAttribute("feedback", feedback.stream().limit(6).toList());
        model.addAttribute("gallery", await(fGallery));
        boolean isRegistered = await(fRegistered);
        model.addAttribute("isRegistered", isRegistered);
        // Extras: bookmark, organizer page, online link visibility, related events, social preview.
        boolean manager = user != null && ("ADMIN".equals(user.getRole())
                || ("ORGANIZER".equals(user.getRole()) && user.getId().equals(event.getOrganizerId())));
        model.addAttribute("mapUrl", display.mapUrl(event));
        model.addAttribute("isSaved", await(fSaved));
        model.addAttribute("organizerPublic", await(fOrganizerPublic));
        model.addAttribute("showOnlineLink", event.getOnlineUrl() != null && !event.getOnlineUrl().isBlank() && (isRegistered || manager));
        model.addAttribute("relatedRecs", await(fRelated));
        String desc = event.getDescription() == null ? "" : event.getDescription().replaceAll("\\s+", " ").trim();
        if (desc.length() > 160) desc = desc.substring(0, 157) + "...";
        if (!draft) model.addAttribute("eventJsonLd", eventJsonLd(event, (String) model.getAttribute("siteUrl"), cover(event)));
        model.addAttribute("ogType", "article");
        model.addAttribute("ogTitle", event.getTitle() + " | Occavie");
        model.addAttribute("ogDescription", (event.getDate() == null ? "" : display.eventDate(event) + " · ")
                + (event.getLocation() == null ? "" : event.getLocation() + ". ") + desc);
        String cover = event.getImageUrl();
        boolean customCover = cover != null && cover.startsWith("/event-images/");
        model.addAttribute("ogImage", (String) model.getAttribute("siteUrl") + (customCover ? cover : "/images/og-default.png"));
        // Waitlist: seats held for offered participants count as taken for everyone else.
        boolean full = !draft && !await(fRoom);
        var waitlistEntry = await(fEntry);
        var offer = await(fOffer);
        model.addAttribute("capacityFull", full);
        model.addAttribute("waitlistEntry", waitlistEntry);
        model.addAttribute("waitlistPosition", waitlistEntry == null ? 0 : waitlistService.position(id, viewerId));
        model.addAttribute("waitlistSize", await(fWaiting));
        model.addAttribute("hasOffer", offer != null);
        model.addAttribute("offerUntil", offer == null ? "" : offer.getOfferExpiresAt()
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a")));
        model.addAttribute("canJoinWaitlist", full && !isRegistered && waitlistEntry == null && user != null
                && "PARTICIPANT".equals(user.getRole()) && com.eventflow.service.EventRules.bookable(event));
        model.addAttribute("myFeedback", isRegistered
                ? feedbackService.findByEventAndParticipant(id, user.getId()).orElse(null)
                : null);
        return "event-details";
    }

    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private static <T> java.util.concurrent.CompletableFuture<T> async(java.util.function.Supplier<T> task) {
        return java.util.concurrent.CompletableFuture.supplyAsync(task, IO);
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw ex;
        }
    }

    private static String cover(Event event) {
        return event.getImageUrl() != null && event.getImageUrl().startsWith("/event-images/") ? event.getImageUrl() : com.eventflow.view.ViewFormatter.coverFor(event);
    }

    /** schema.org/Event structured data. Every value is JSON-escaped and angle brackets are \\u-escaped so it is safe inside a script tag. */
    static String eventJsonLd(Event event, String siteUrl, String imagePath) {
        StringBuilder json = new StringBuilder("{\"@context\":\"https://schema.org\",\"@type\":\"Event\"");
        json.append(",\"name\":").append(jsonString(event.getTitle()));
        if (event.getDate() != null) {
            json.append(",\"startDate\":").append(jsonString(event.getDate() + (event.getTime() == null ? "" : "T" + event.getTime())));
        }
        json.append(",\"eventStatus\":\"https://schema.org/").append(com.eventflow.service.EventRules.isCancelled(event) ? "EventCancelled" : "EventScheduled").append('"');
        boolean online = event.getOnlineUrl() != null && !event.getOnlineUrl().isBlank();
        json.append(",\"eventAttendanceMode\":\"https://schema.org/").append(online ? "MixedEventAttendanceMode" : "OfflineEventAttendanceMode").append('"');
        json.append(",\"location\":{\"@type\":\"Place\",\"name\":").append(jsonString(event.getLocation()));
        if (event.getAddress() != null && !event.getAddress().isBlank()) json.append(",\"address\":").append(jsonString(event.getAddress()));
        json.append('}');
        if (event.getDescription() != null) json.append(",\"description\":").append(jsonString(event.getDescription()));
        json.append(",\"image\":").append(jsonString(siteUrl + imagePath));
        if (event.getOrganizerName() != null) json.append(",\"organizer\":{\"@type\":\"Organization\",\"name\":").append(jsonString(event.getOrganizerName())).append('}');
        return json.append(",\"url\":").append(jsonString(siteUrl + "/events/" + event.getId())).append('}').toString();
    }

    static String jsonString(String value) {
        if (value == null) return "\"\"";
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    private Map<String, String> startingPriceLabels(List<Event> events) {
        return startingPriceLabels(events, ticketTypeService.byEvents(events));
    }

    private Map<String, String> startingPriceLabels(List<Event> events,
            Map<String, List<com.eventflow.model.EventTicketType>> tiers) {
        Map<String, String> labels = new HashMap<>();
        for (Event event : events) {
            labels.put(event.getId(), ticketTypeService.startingPriceLabel(event, tiers.get(event.getId())));
        }
        return labels;
    }

    private User currentUserOrNull(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return userService.currentUser(authentication);
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "access-denied";
    }

    @GetMapping("/dashboard")
    public String dashboardRedirect(Authentication authentication) {
        User user = userService.currentUser(authentication);
        return switch (user.getRole()) {
            case "ADMIN" -> "redirect:/admin/dashboard";
            case "ORGANIZER" -> "redirect:/organizer/dashboard";
            default -> "redirect:/participant/dashboard";
        };
    }

    @GetMapping("/participant/dashboard")
    public String participantDashboard(Authentication authentication, Model model) {
        User user = userService.currentUser(authentication);
        List<Event> events = eventService.findAllSorted();
        List<Registration> registrations = registrationService.findByParticipant(user.getId());
        Set<String> ids = registrations.stream()
                .map(Registration::getEventId)
                .collect(java.util.stream.Collectors.toSet());
        List<Event> registeredEvents = events.stream().filter(event -> ids.contains(event.getId())).toList();

        model.addAttribute("user", user);
        model.addAttribute("metricOneLabel", "Available Events");
        model.addAttribute("metricOneValue", events.stream().filter(eventService::isUpcoming).count());
        model.addAttribute("metricTwoLabel", "My Registrations");
        model.addAttribute("metricTwoValue", registrations.size());
        model.addAttribute("metricThreeLabel", "Upcoming Registered");
        model.addAttribute("metricThreeValue", registeredEvents.stream().filter(eventService::isUpcoming).count());
        model.addAttribute("metricFourLabel", "Completed");
        model.addAttribute("metricFourValue", registeredEvents.stream().filter(eventService::isCompleted).count());
        List<Event> dashboardEvents = registeredEvents.stream().filter(eventService::isUpcoming).limit(5).toList();
        model.addAttribute("dashboardEvents", dashboardEvents);
        model.addAttribute("registrationCounts", eventService.registrationCounts(dashboardEvents));
        // Real data only: this participant's own bookings, completed events and notifications.
        model.addAttribute("registrationByEvent", registrations.stream()
                .collect(java.util.stream.Collectors.toMap(Registration::getEventId, r -> r, (a, b) -> a)));
        model.addAttribute("previousEvents", registeredEvents.stream().filter(eventService::isCompleted)
                .sorted(java.util.Comparator.comparing(Event::getDate).reversed()).limit(5).toList());
        model.addAttribute("recentNotifications", notificationService.findRecentForUser(user.getId()).stream().limit(5).toList());
        model.addAttribute("savedEvents", savedEventService.savedEvents(user).stream().limit(6).toList());
        var recommendations = suggestionService.forParticipant(user, 8);
        addPhotoCards(model, "Recommended for you", recommendations.isEmpty() ? "Upcoming events you have not booked yet"
                        : "Picked from what you saved, booked and what is happening soon",
                recommendations.stream().map(com.eventflow.service.RecommendationEngine.Recommendation::event).toList());
        Map<String, java.util.List<String>> why = new java.util.HashMap<>();
        for (var r : recommendations) why.put(r.event().getId(), r.reasons());
        model.addAttribute("cardWhy", why);
        return "dashboard";
    }

    @GetMapping("/organizer/dashboard")
    public String organizerDashboard(Authentication authentication, Model model) {
        User user = userService.currentUser(authentication);
        List<Event> events = eventService.findByOrganizer(user.getId());
        long totalRegistrations = registrationService.countsForEvents(
                events.stream().map(Event::getId).toList()).values().stream()
                .mapToLong(Long::longValue).sum();

        model.addAttribute("user", user);
        model.addAttribute("metricOneLabel", "My Events");
        model.addAttribute("metricOneValue", events.size());
        model.addAttribute("metricTwoLabel", "Upcoming Events");
        model.addAttribute("metricTwoValue", events.stream().filter(eventService::isUpcoming).count());
        model.addAttribute("metricThreeLabel", "Total Registrations");
        model.addAttribute("metricThreeValue", totalRegistrations);
        model.addAttribute("metricFourLabel", "Completed Events");
        model.addAttribute("metricFourValue", events.stream().filter(eventService::isCompleted).count());
        List<Event> dashboardEvents = events.stream().limit(5).toList();
        model.addAttribute("dashboardEvents", dashboardEvents);
        model.addAttribute("registrationCounts", eventService.registrationCounts(dashboardEvents));
        model.addAttribute("analytics", analyticsService.forEvents(events));
        addPhotoCards(model, "Your events", "Your upcoming events at a glance",
                events.stream().filter(eventService::isUpcoming).limit(8).toList());
        // Copilot: the events that would gain most from a few fixes (drafts and upcoming only).
        List<Event> active = events.stream().filter(e -> "DRAFT".equals(e.getStatus()) || eventService.isUpcoming(e)).limit(12).toList();
        var readinessByEvent = readinessService.assessAll(active);
        List<Event> attention = active.stream().filter(e -> readinessByEvent.get(e.getId()).score() < 75)
                .sorted(java.util.Comparator.comparingInt(e -> readinessByEvent.get(e.getId()).score())).limit(3).toList();
        model.addAttribute("copilotAttention", attention);
        model.addAttribute("copilotScores", readinessByEvent);
        return "dashboard";
    }

    @GetMapping("/admin/dashboard")
    public String adminDashboard(Authentication authentication, Model model) {
        User user = userService.currentUser(authentication);
        List<Event> events = eventService.findAllSorted();

        model.addAttribute("user", user);
        model.addAttribute("metricOneLabel", "Total Users");
        model.addAttribute("metricOneValue", userService.count());
        model.addAttribute("metricTwoLabel", "Organizers");
        model.addAttribute("metricTwoValue", userService.countByRole("ORGANIZER"));
        model.addAttribute("metricThreeLabel", "Total Events");
        model.addAttribute("metricThreeValue", eventService.count());
        model.addAttribute("metricFourLabel", "Registrations");
        model.addAttribute("metricFourValue", registrationService.count());
        model.addAttribute("participantCount", userService.countByRole("PARTICIPANT"));
        List<Event> dashboardEvents = events.stream().limit(5).toList();
        model.addAttribute("dashboardEvents", dashboardEvents);
        model.addAttribute("registrationCounts", eventService.registrationCounts(dashboardEvents));
        model.addAttribute("recentUsers", userService.findDashboardPreview());
        model.addAttribute("analytics", analyticsService.forEvents(events));
        addPhotoCards(model, "Upcoming on Occavie", "The next events across the platform",
                events.stream().filter(eventService::isUpcoming).limit(8).toList());
        return "dashboard";
    }

    private void addPhotoCards(Model model, String heading, String subheading, List<Event> cards) {
        model.addAttribute("cardHeading", heading);
        model.addAttribute("cardSubheading", subheading);
        model.addAttribute("cardEvents", cards);
        model.addAttribute("cardPrices", startingPriceLabels(cards));
    }
}
