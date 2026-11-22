package com.eventflow.controller;

import com.eventflow.dto.EventDraftForm;
import com.eventflow.model.Event;
import com.eventflow.model.User;
import com.eventflow.service.EventProgramService;
import com.eventflow.service.EventService;
import com.eventflow.service.NotificationService;
import com.eventflow.service.TicketTypeService;
import com.eventflow.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/**
 * Six-step event wizard. Each step saves to a private DRAFT event, so progress is never lost and the organizer can
 * continue later. All routes are under /organizer/** (ORGANIZER role only) and every event access goes through
 * {@code EventService.findOwned}, so organizers can only touch their own events.
 */
@Controller
@RequiredArgsConstructor
public class EventWizardController {
    static final int LAST_STEP = 6;
    private static final java.util.List<String> STEP_LABELS =
            java.util.List.of("Basics", "Date & venue", "Cover & description", "Program", "Tickets", "Review");
    private static final java.util.List<String> CATEGORIES = java.util.List.of(
            "Technology", "Business", "Education", "Health", "Design", "Music", "Career", "Sports", "Community", "Arts", "Science", "Food", "Charity", "Other");

    private final UserService userService;
    private final EventService eventService;
    private final com.eventflow.service.ReadinessService readinessService;
    private final EventProgramService eventProgramService;
    private final TicketTypeService ticketTypeService;
    private final NotificationService notificationService;

    @GetMapping("/organizer/events/new")
    public String start(Model model) {
        model.addAttribute("step", 1);
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("stepLabels", STEP_LABELS);
        model.addAttribute("categories", CATEGORIES);
        return "event-wizard";
    }

    @PostMapping("/organizer/events/wizard")
    public String create(Authentication authentication, @ModelAttribute EventDraftForm form,
                         @RequestParam(defaultValue = "next") String action, RedirectAttributes flash) {
        try {
            Event draft = eventService.createDraft(form, userService.currentUser(authentication));
            if ("draft".equals(action)) {
                flash.addFlashAttribute("toastSuccess", "Draft saved. Continue any time from My Events.");
                return "redirect:/organizer/events";
            }
            return "redirect:/organizer/events/" + draft.getId() + "/wizard?step=2";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events/new";
        }
    }

    @GetMapping("/organizer/events/{id}/wizard")
    public String step(@PathVariable String id, @RequestParam(defaultValue = "1") int step,
                       Authentication authentication, Model model, RedirectAttributes flash) {
        User organizer = userService.currentUser(authentication);
        try {
            Event event = eventService.findOwned(id, organizer);
            if ("PENDING_APPROVAL".equals(event.getStatus())) {
                flash.addFlashAttribute("toastError", "This event is awaiting approval and is locked. Withdraw it to make changes.");
                return "redirect:/organizer/events";
            }
            if (!com.eventflow.service.EventRules.isEditableDraft(event)) {
                return "redirect:/organizer/events/" + id + "/edit";
            }
            model.addAttribute("approvalRequired", eventService.isApprovalRequired());
            model.addAttribute("event", event);
            model.addAttribute("step", Math.max(1, Math.min(LAST_STEP, step)));
            model.addAttribute("today", LocalDate.now());
            model.addAttribute("stepLabels", STEP_LABELS);
            model.addAttribute("categories", CATEGORIES);
            model.addAttribute("sessionCount", eventProgramService.findSchedule(id).size());
            model.addAttribute("speakerCount", eventProgramService.findSpeakers(id).size());
            model.addAttribute("sponsorCount", eventProgramService.findSponsors(id).size());
            model.addAttribute("ticketTypes", ticketTypeService.findByEvent(id));
            model.addAttribute("missing", eventService.readiness(event));
            if (step == 6) {
                var copilot = readinessService.assess(event);
                java.util.Map<String, String> copilotLinks = new java.util.HashMap<>();
                copilot.checks().forEach(c -> copilotLinks.put(c.id(), com.eventflow.service.ReadinessService.fixLink(event, c.area())));
                model.addAttribute("copilot", copilot);
                model.addAttribute("copilotLinks", copilotLinks);
            }
            return "event-wizard";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events";
        }
    }

    @PostMapping("/organizer/events/{id}/wizard/{step}")
    public String save(@PathVariable String id, @PathVariable int step, @ModelAttribute EventDraftForm form,
                       @RequestParam(defaultValue = "next") String action,
                       Authentication authentication, RedirectAttributes flash) {
        User organizer = userService.currentUser(authentication);
        try {
            eventService.updateDraft(id, form, organizer);
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events/" + id + "/wizard?step=" + step;
        }
        if ("draft".equals(action)) {
            flash.addFlashAttribute("toastSuccess", "Draft saved. Continue any time from My Events.");
            return "redirect:/organizer/events";
        }
        int target = "back".equals(action) ? step - 1 : step + 1;
        return "redirect:/organizer/events/" + id + "/wizard?step=" + Math.max(1, Math.min(LAST_STEP, target));
    }

    @PostMapping("/organizer/events/{id}/publish")
    public String publish(@PathVariable String id, Authentication authentication, RedirectAttributes flash) {
        User organizer = userService.currentUser(authentication);
        try {
            Event event = eventService.publish(id, organizer);
            for (User admin : userService.findByRole("ADMIN")) {
                notificationService.notifyUser(admin.getId(),
                        organizer.getFullName() + " published a new event: " + event.getTitle() + ".", "/events/" + event.getId());
            }
            flash.addFlashAttribute("toastSuccess", "Event published.");
            return "redirect:/organizer/events";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events/" + id + "/wizard?step=6";
        }
    }

    @PostMapping("/organizer/events/{id}/submit")
    public String submit(@PathVariable String id, Authentication authentication, RedirectAttributes flash) {
        User organizer = userService.currentUser(authentication);
        try {
            Event event = eventService.submitForApproval(id, organizer);
            for (User admin : userService.findByRole("ADMIN")) {
                notificationService.notify(com.eventflow.service.NotificationType.APPROVAL_UPDATE, admin.getId(), event.getId(), event.getTitle(),
                        organizer.getFullName() + " submitted \"" + event.getTitle() + "\" for approval.", "/admin/approvals",
                        "SUBMITTED:" + event.getId() + ":" + event.getSubmittedAt(), null);
            }
            flash.addFlashAttribute("toastSuccess", "Submitted for approval. You will be notified when an administrator reviews it.");
            return "redirect:/organizer/events";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events/" + id + "/wizard?step=6";
        }
    }

    @PostMapping("/organizer/events/{id}/withdraw")
    public String withdraw(@PathVariable String id, Authentication authentication, RedirectAttributes flash) {
        try {
            eventService.withdraw(id, userService.currentUser(authentication));
            flash.addFlashAttribute("toastSuccess", "Event withdrawn. It is a draft again.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
        }
        return "redirect:/organizer/events";
    }

    @PostMapping("/organizer/events/{id}/duplicate")
    public String duplicate(@PathVariable String id, Authentication authentication, RedirectAttributes flash) {
        try {
            Event copy = eventService.duplicate(id, userService.currentUser(authentication));
            flash.addFlashAttribute("toastSuccess", "Draft created from your event. Choose a new date and time to publish it.");
            return "redirect:/organizer/events/" + copy.getId() + "/wizard?step=2";
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
            return "redirect:/organizer/events";
        }
    }

    @PostMapping("/organizer/events/{id}/cancel")
    public String cancel(@PathVariable String id, @RequestParam(required = false) String reason,
                         Authentication authentication, RedirectAttributes flash) {
        try {
            eventService.cancelOwned(id, reason, userService.currentUser(authentication));
            flash.addFlashAttribute("toastSuccess", "Event cancelled. Registered participants and the waitlist were notified.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("toastError", ex.getMessage());
        }
        return "redirect:/organizer/events";
    }
}
