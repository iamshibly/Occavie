package com.eventflow.service;

import com.eventflow.model.Event;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** Event lifecycle and pricing rules; no view formatting or database calls. */
public final class EventRules {
    private EventRules() {}

    public static String status(Event event) {
        if (event == null) return "UPCOMING";
        // Lifecycle states take precedence over the date-derived COMPLETED state.
        if (isLifecycleState(event.getStatus())) return event.getStatus();
        LocalDate date = event.getDate();
        if (date != null) {
            LocalDate today = LocalDate.now();
            if (date.isBefore(today)
                    || (date.isEqual(today) && event.getTime() != null
                    && event.getTime().isBefore(LocalTime.now().withSecond(0).withNano(0)))) {
                return "COMPLETED";
            }
        }
        String status = event.getStatus();
        return status == null || status.isBlank() ? "UPCOMING" : status;
    }

    /** Draft, pending approval, rejected and cancelled override the date-derived COMPLETED state. */
    private static boolean isLifecycleState(String status) {
        return "CANCELLED".equals(status) || "DRAFT".equals(status) || "PENDING_APPROVAL".equals(status) || "REJECTED".equals(status);
    }

    /** Visible to the public. Drafts, events awaiting approval and rejected events are private to their organizer and admins. */
    public static boolean isPublished(Event event) {
        return event != null && !("DRAFT".equals(event.getStatus()) || "PENDING_APPROVAL".equals(event.getStatus())
                || "REJECTED".equals(event.getStatus()));
    }

    /** The organizer can still change it step by step (draft or rejected). */
    public static boolean isEditableDraft(Event event) {
        return event != null && ("DRAFT".equals(event.getStatus()) || "REJECTED".equals(event.getStatus()));
    }

    /** Open for registration: published, not cancelled and not yet finished. */
    public static boolean bookable(Event event) {
        return event != null && "UPCOMING".equals(status(event));
    }

    public static boolean isCancelled(Event event) {
        return event != null && "CANCELLED".equals(event.getStatus());
    }

    public static String pricingType(Event event) {
        String raw = event == null ? null : event.getPricingType();
        return raw == null || raw.isBlank() ? "FREE" : raw;
    }

    public static boolean isPaid(Event event) {
        return "PAID".equalsIgnoreCase(pricingType(event));
    }

    public static BigDecimal basePrice(Event event) {
        return event == null || event.getBasePrice() == null
                ? BigDecimal.ZERO : event.getBasePrice();
    }
}
