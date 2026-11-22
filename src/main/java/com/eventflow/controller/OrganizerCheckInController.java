package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.model.User;
import com.eventflow.service.CheckInService;
import com.eventflow.service.EventService;
import com.eventflow.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/** Organizer check-in. URL access is ORGANIZER-only (SecurityConfig) and CheckInService re-checks event ownership. */
@Controller
@RequiredArgsConstructor
public class OrganizerCheckInController {
    private final UserService userService;
    private final EventService eventService;
    private final CheckInService checkInService;

    @GetMapping("/organizer/events/{eventId}/check-in")
    public String page(@PathVariable String eventId, Authentication authentication, Model model, RedirectAttributes flash) {
        User organizer = userService.currentUser(authentication);
        try {
            Event event = eventService.findOwned(eventId, organizer);
            model.addAttribute("event", event);
            model.addAttribute("stats", checkInService.stats(eventId, organizer).orElseThrow());
            return "organizer-checkin";
        } catch (RuntimeException ex) {
            flash.addFlashAttribute("toastError", "You cannot manage this event.");
            return "redirect:/organizer/events";
        }
    }

    public record ScanRequest(String token) { }

    @PostMapping("/organizer/events/{eventId}/check-in/scan")
    @ResponseBody
    public ResponseEntity<CheckInService.Result> scan(@PathVariable String eventId, @RequestBody ScanRequest request,
                                                      Authentication authentication) {
        return respond(checkInService.checkInByToken(eventId, request == null ? null : request.token(),
                userService.currentUser(authentication)));
    }

    @PostMapping("/organizer/events/{eventId}/check-in/registrations/{registrationId}")
    @ResponseBody
    public ResponseEntity<CheckInService.Result> manual(@PathVariable String eventId, @PathVariable String registrationId,
                                                        Authentication authentication) {
        return respond(checkInService.checkInRegistration(eventId, registrationId, userService.currentUser(authentication)));
    }

    @GetMapping("/organizer/events/{eventId}/check-in/search")
    @ResponseBody
    public List<CheckInService.Row> search(@PathVariable String eventId, @RequestParam(defaultValue = "") String q,
                                           Authentication authentication) {
        return checkInService.search(eventId, q, userService.currentUser(authentication));
    }

    @GetMapping("/organizer/events/{eventId}/check-in/stats")
    @ResponseBody
    public ResponseEntity<?> stats(@PathVariable String eventId, Authentication authentication) {
        return checkInService.stats(eventId, userService.currentUser(authentication))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Not allowed")));
    }

    private ResponseEntity<CheckInService.Result> respond(CheckInService.Result result) {
        HttpStatus status = result.status() == CheckInService.Status.FORBIDDEN ? HttpStatus.FORBIDDEN : HttpStatus.OK;
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(result);
    }
}
