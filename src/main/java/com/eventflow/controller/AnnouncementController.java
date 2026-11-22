package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.model.User;
import com.eventflow.service.AnnouncementService;
import com.eventflow.service.EventService;
import com.eventflow.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Organizer announcements (URL is ORGANIZER-only; ownership is re-checked in the service). */
@Controller
@RequiredArgsConstructor
public class AnnouncementController {
    private final UserService userService;
    private final EventService eventService;
    private final AnnouncementService announcementService;

    @GetMapping("/organizer/events/{eventId}/announcements")
    public String page(@PathVariable String eventId, Authentication authentication, Model model, RedirectAttributes flash) {
        User organizer = userService.currentUser(authentication);
        try {
            Event event = eventService.findOwned(eventId, organizer);
            model.addAttribute("event", event);
            model.addAttribute("announcements", announcementService.history(eventId, organizer));
            return "event-announcements";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events";
        }
    }

    @PostMapping("/organizer/events/{eventId}/announcements")
    public String send(@PathVariable String eventId, @RequestParam(defaultValue = "") String subject,
                       @RequestParam(defaultValue = "") String message, Authentication authentication, RedirectAttributes flash) {
        try {
            var sent = announcementService.send(eventId, userService.currentUser(authentication), subject, message);
            flash.addFlashAttribute("toastSuccess", "Announcement sent to " + sent.getRecipientCount()
                    + (sent.getRecipientCount() == 1 ? " attendee." : " attendees."));
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
        }
        return "redirect:/organizer/events/" + eventId + "/announcements";
    }
}
