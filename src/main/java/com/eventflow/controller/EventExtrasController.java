package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.model.EventScheduleItem;
import com.eventflow.model.User;
import com.eventflow.service.EventProgramService;
import com.eventflow.service.EventRules;
import com.eventflow.service.EventService;
import com.eventflow.service.IcsBuilder;
import com.eventflow.service.OrganizerProfileService;
import com.eventflow.service.SavedEventService;
import com.eventflow.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Locale;

/** Saved events, calendar download and public organizer pages. */
@Controller
@RequiredArgsConstructor
public class EventExtrasController {
    private final UserService userService;
    private final EventService eventService;
    private final EventProgramService programService;
    private final SavedEventService savedEventService;
    private final OrganizerProfileService organizerProfileService;

    @Value("${occavie.event-timezone:Asia/Dhaka}")
    private String eventTimezone;

    // ---------------------------------------------------------------- saved events (participants only: /participant/**)

    @PostMapping("/participant/events/{eventId}/save")
    public String save(@PathVariable String eventId, @RequestParam(defaultValue = "event") String from,
                       Authentication authentication, RedirectAttributes flash) {
        try {
            savedEventService.save(userService.currentUser(authentication), eventId);
            flash.addFlashAttribute("toastSuccess", "Saved to your dashboard.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
        }
        return back(eventId, from);
    }

    @PostMapping("/participant/events/{eventId}/unsave")
    public String unsave(@PathVariable String eventId, @RequestParam(defaultValue = "event") String from,
                         Authentication authentication, RedirectAttributes flash) {
        try {
            savedEventService.unsave(userService.currentUser(authentication), eventId);
            flash.addFlashAttribute("toastSuccess", "Removed from saved events.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
        }
        return back(eventId, from);
    }

    /** Used by the Save button: toggles without a page reload. Returns {"saved": true|false}. */
    @PostMapping("/participant/events/{eventId}/saved-toggle")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> toggle(@PathVariable String eventId,
                                                                                          Authentication authentication) {
        try {
            User user = userService.currentUser(authentication);
            boolean saved = savedEventService.isSaved(user, eventId);
            if (saved) savedEventService.unsave(user, eventId); else savedEventService.save(user, eventId);
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("saved", !saved));
        } catch (IllegalArgumentException ex) {
            return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        }
    }

    private String back(String eventId, String from) {
        return "dashboard".equals(from) ? "redirect:/participant/dashboard" : "redirect:/events/" + eventId;
    }

    // ---------------------------------------------------------------- calendar

    @GetMapping("/events/{id}/calendar.ics")
    public ResponseEntity<byte[]> calendar(@PathVariable String id, HttpServletRequest request) {
        Event event = eventService.findOptional(id).filter(EventRules::isPublished)
                .filter(e -> e.getDate() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        ZoneId zone = ZoneId.of(eventTimezone);
        LocalDateTime end = programService.findSchedule(id).stream()
                .map(EventScheduleItem::getEndTime).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .filter(t -> event.getTime() != null && t.isAfter(event.getTime()))
                .map(t -> LocalDateTime.of(event.getDate(), t)).orElse(null);
        String url = ServletUriComponentsBuilder.fromContextPath(request).path("/events/" + id).toUriString();
        byte[] body = IcsBuilder.build(event, end, zone, url, Instant.now()).getBytes(StandardCharsets.UTF_8);
        String slug = (event.getTitle() == null ? "event" : event.getTitle()).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"occavie-" + (slug.isEmpty() ? "event" : slug) + ".ics\"")
                .cacheControl(CacheControl.noCache())
                .body(body);
    }

    // ---------------------------------------------------------------- public organizer page

    @GetMapping("/organizers/{id}")
    public String organizer(@PathVariable String id, Model model) {
        var profile = organizerProfileService.publicProfile(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organizer not found"));
        model.addAttribute("organizer", profile.organizer());
        model.addAttribute("upcoming", profile.upcoming());
        model.addAttribute("past", profile.past());
        model.addAttribute("ogTitle", profile.organizer().getFullName() + " on Occavie");
        model.addAttribute("ogDescription", profile.organizer().getBio() != null ? profile.organizer().getBio()
                : "Events hosted by " + profile.organizer().getFullName() + " on Occavie.");
        return "organizer-public";
    }

    // ---------------------------------------------------------------- organizer edits their public profile

    @PostMapping("/profile/organizer")
    public String updateOrganizerProfile(Authentication authentication, @RequestParam(defaultValue = "") String bio,
                                         @RequestParam(defaultValue = "") String website,
                                         @RequestParam(defaultValue = "") String publicContactEmail,
                                         RedirectAttributes flash) {
        User user = userService.currentUser(authentication);
        try {
            organizerProfileService.updatePublicProfile(user, bio, website, publicContactEmail);
            flash.addFlashAttribute("toastSuccess", "Public profile updated.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
        }
        return "redirect:/profile";
    }
}
