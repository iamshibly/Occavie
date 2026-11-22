package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.model.User;
import com.eventflow.service.EventService;
import com.eventflow.service.ReadinessEngine;
import com.eventflow.service.ReadinessService;
import com.eventflow.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

/** Organizer Copilot page: readiness score, what to fix next, and growth tips for published events. */
@Controller
@RequiredArgsConstructor
public class CopilotController {
    private final EventService eventService;
    private final UserService userService;
    private final ReadinessService readinessService;

    @GetMapping("/organizer/events/{id}/copilot")
    public String copilot(@PathVariable String id, Authentication authentication, Model model, RedirectAttributes flash) {
        User organizer = userService.currentUser(authentication);
        Event event;
        try {
            event = eventService.findOwned(id, organizer);               // only the owning organizer
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events";
        }
        ReadinessEngine.Readiness readiness = readinessService.assess(event);
        Map<String, String> links = new HashMap<>();
        readiness.checks().forEach(c -> links.put(c.id(), ReadinessService.fixLink(event, c.area())));
        model.addAttribute("user", organizer);
        model.addAttribute("event", event);
        model.addAttribute("readiness", readiness);
        model.addAttribute("fixLinks", links);
        model.addAttribute("published", com.eventflow.service.EventRules.isPublished(event));
        model.addAttribute("insights", com.eventflow.service.EventRules.isPublished(event) ? readinessService.insights(event) : java.util.List.of());
        return "organizer-copilot";
    }
}
