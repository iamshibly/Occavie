package com.eventflow.service;

/**
 * Kinds of notifications. {@code emailKind} says whether and how the message is also emailed:
 * ESSENTIAL emails are booking-related and always sent; REMINDER and ANNOUNCEMENT follow the user's preferences;
 * NONE is in-app only.
 */
public enum NotificationType {
    REGISTRATION_CONFIRMED(EmailKind.ESSENTIAL, "bi-ticket-perforated", "Registration confirmed"),
    REGISTRATION_CANCELLED(EmailKind.ESSENTIAL, "bi-x-circle", "Registration cancelled"),
    EVENT_RESCHEDULED(EmailKind.ESSENTIAL, "bi-calendar2-week", "Event updated"),
    EVENT_CANCELLED(EmailKind.ESSENTIAL, "bi-slash-circle", "Event cancelled"),
    WAITLIST_OFFER(EmailKind.ESSENTIAL, "bi-stopwatch", "A seat is available"),
    EVENT_REMINDER(EmailKind.REMINDER, "bi-alarm", "Event reminder"),
    ANNOUNCEMENT(EmailKind.ANNOUNCEMENT, "bi-megaphone", "Announcement"),
    APPROVAL_UPDATE(EmailKind.ESSENTIAL, "bi-patch-check", "Event review"),
    PASSWORD_RESET(EmailKind.ESSENTIAL, "bi-key", "Reset your password"),
    GENERAL(EmailKind.NONE, "bi-bell", "Notification");

    public enum EmailKind { ESSENTIAL, REMINDER, ANNOUNCEMENT, NONE }

    private final EmailKind emailKind;
    private final String icon;
    private final String label;

    NotificationType(EmailKind emailKind, String icon, String label) {
        this.emailKind = emailKind;
        this.icon = icon;
        this.label = label;
    }

    public EmailKind emailKind() { return emailKind; }
    public String icon() { return icon; }
    public String label() { return label; }

    public static NotificationType of(String name) {
        try { return name == null ? GENERAL : valueOf(name); } catch (IllegalArgumentException ex) { return GENERAL; }
    }
}
