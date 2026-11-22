package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.model.User;
import com.eventflow.service.EventService;
import com.eventflow.service.UserService;
import com.eventflow.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Organizers see who is waiting for their own events (ownership enforced by findOwned). */
@Controller
@RequiredArgsConstructor
public class OrganizerWaitlistController {
    private final UserService userService;
    private final EventService eventService;
    private final WaitlistService waitlistService;

    @GetMapping("/organizer/events/{id}/waitlist")
    public String waitlist(@PathVariable String id, Authentication authentication, Model model, RedirectAttributes flash) {
        User organizer = userService.currentUser(authentication);
        try {
            Event event = eventService.findOwned(id, organizer);
            var entries = waitlistService.activeEntries(id);
            model.addAttribute("event", event);
            model.addAttribute("entries", entries);
            model.addAttribute("freeSeats", Math.max(0, waitlistService.freeSeats(event)));
            model.addAttribute("reservedSeats", waitlistService.activeOffers(id));
            return "event-waitlist";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events";
        }
    }
}
