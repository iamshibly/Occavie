package com.eventflow.view;

import com.eventflow.model.ContactRequest;
import com.eventflow.model.Coupon;
import com.eventflow.model.Event;
import com.eventflow.model.EventScheduleItem;
import com.eventflow.model.EventSponsor;
import com.eventflow.model.EventTicketType;
import com.eventflow.model.Feedback;
import com.eventflow.model.Notification;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.service.CouponRules;
import com.eventflow.service.EventRules;
import com.eventflow.service.RegistrationRules;
import com.eventflow.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Read-only presentation values for Thymeleaf; MongoDB models remain data-only. */
@Component("display")
@RequiredArgsConstructor
public class ViewFormatter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");
    private static final Map<String, String> REQUEST_TYPES = Map.of(
            "GENERAL_INQUIRY", "General Inquiry",
            "EVENT_SUPPORT", "Event Support",
            "REGISTRATION_ISSUE", "Registration Issue",
            "COUPON_PRICING_ISSUE", "Coupon / Pricing Issue",
            "ORGANIZER_SUPPORT", "Organizer Support",
            "TECHNICAL_PROBLEM", "Technical Problem",
            "OTHER", "Other");

    private final UserService userService;

    public String status(Event event) { return EventRules.status(event); }
    public boolean paid(Event event) { return EventRules.isPaid(event); }
    /** Human-readable status for badges: Draft, Pending approval, Published (upcoming), Rejected, Cancelled, Completed. */
    public String statusLabel(Event event) {
        return switch (EventRules.status(event)) {
            case "PENDING_APPROVAL" -> "Pending approval";
            case "UPCOMING" -> "Published";
            case "DRAFT" -> "Draft";
            case "REJECTED" -> "Rejected";
            case "CANCELLED" -> "Cancelled";
            case "COMPLETED" -> "Completed";
            default -> EventRules.status(event);
        };
    }

    public String pricingType(Event event) { return EventRules.pricingType(event); }
    public BigDecimal basePrice(Event event) { return EventRules.basePrice(event); }
    /** Google Maps search link built from the address (or venue name); null when there is nothing to search. */
    public String mapUrl(Event event) {
        if (event == null) return null;
        String query = event.getAddress() != null && !event.getAddress().isBlank()
                ? event.getLocation() + ", " + event.getAddress() : event.getLocation();
        if (query == null || query.isBlank()) return null;
        return "https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(query.trim(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public String eventDate(Event event) { return date(event == null ? null : event.getDate()); }
    public String eventTime(Event event) { return time(event == null ? null : event.getTime()); }
    public String formattedBasePrice(Event event) {
        BigDecimal price = EventRules.basePrice(event);
        return !EventRules.isPaid(event) || price.signum() <= 0 ? "Free" : bdt(price);
    }
    /**
     * Preferred real photos per category (files in static/images/photos/). Names that are not present on the classpath
     * are skipped, so adding a photo with one of these names is enough: no code change needed.
     */
    private static final java.util.Map<String, java.util.List<String>> PHOTO_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("technology", java.util.List.of("tech-lock", "laptop-bw", "workshop-room", "workshop", "conference")),
            java.util.Map.entry("business", java.util.List.of("business-laptop", "market-stall", "networking", "speaker-hall", "conference")),
            java.util.Map.entry("education", java.util.List.of("panel", "speaker-hall", "workshop-room", "workshop", "business-laptop")),
            java.util.Map.entry("career", java.util.List.of("career-fair", "networking", "business-laptop")),
            java.util.Map.entry("design", java.util.List.of("design-desk", "market-stall", "business-laptop")),
            java.util.Map.entry("music", java.util.List.of("music", "dance", "conference")),
            java.util.Map.entry("community", java.util.List.of("volunteers", "dance", "networking", "workshop")),
            java.util.Map.entry("sports", java.util.List.of("marathon")),
            java.util.Map.entry("arts", java.util.List.of("market-stall", "dance", "design-desk")),
            java.util.Map.entry("science", java.util.List.of("workshop-room", "panel", "tech-lock")),
            java.util.Map.entry("food", java.util.List.of("market-stall")),
            java.util.Map.entry("charity", java.util.List.of("volunteers", "networking")));
    private static final java.util.Map<String, java.util.List<String>> PHOTOS = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.List<String> photosFor(String category) {
        return PHOTOS.computeIfAbsent(category, c -> PHOTO_NAMES.getOrDefault(c, java.util.List.of()).stream()
                .filter(n -> new org.springframework.core.io.ClassPathResource("static/images/photos/" + n + ".webp").exists())
                .toList());
    }

    /** The event's own photo, or a category photo (variant chosen by event) when none was uploaded. */
    public String listingImageUrl(Event event) {
        if (event != null && event.getImageUrl() != null && !event.getImageUrl().isBlank()
                && !"/images/event-visual.png".equals(event.getImageUrl())) {
            return event.getImageUrl();
        }
        return coverFor(event);
    }

    public static String coverFor(Event event) {
        String category = event == null || event.getCategory() == null ? "" : event.getCategory().trim().toLowerCase(java.util.Locale.ROOT);
        String key = (event == null || event.getId() == null ? "" : event.getId()) + (event == null || event.getTitle() == null ? "" : event.getTitle());
        int hash = Math.floorMod(key.hashCode(), 1000);
        java.util.List<String> photos = photosFor(category);
        if (!photos.isEmpty()) return "/images/photos/" + photos.get(hash % photos.size()) + ".webp";
        String slug = "health".equals(category) || "sports".equals(category) ? category : "other";
        return "/images/covers/" + slug + "-" + (hash % 3 + 1) + ".webp";
    }

    public String ticketPrice(EventTicketType ticket) {
        return freeOrMoney(ticket == null ? null : ticket.getPrice());
    }
    public String discount(Coupon coupon) {
        BigDecimal amount = coupon == null || coupon.getDiscountValue() == null
                ? BigDecimal.ZERO : coupon.getDiscountValue();
        String number = normalizedAmount(amount);
        return coupon != null && "PERCENT".equalsIgnoreCase(coupon.getDiscountType())
                ? number + "% OFF" : "৳" + number + " OFF";
    }
    public String couponExpiry(Coupon coupon) {
        return coupon == null || coupon.getExpiryDate() == null ? "No expiry" : date(coupon.getExpiryDate());
    }
    public String couponStatus(Coupon coupon) {
        if (coupon == null || !coupon.isActive()) return "PAUSED";
        return CouponRules.expired(coupon) ? "EXPIRED" : "ACTIVE";
    }
    public String sponsorLevel(EventSponsor sponsor) {
        if (sponsor == null || sponsor.getLevel() == null || sponsor.getLevel().isBlank()) return "Partner";
        return switch (sponsor.getLevel().toUpperCase(java.util.Locale.ROOT)) {
            case "TITLE" -> "Title Sponsor";
            case "GOLD" -> "Gold Sponsor";
            case "SILVER" -> "Silver Sponsor";
            case "BRONZE" -> "Bronze Sponsor";
            default -> "Partner";
        };
    }
    public String scheduleStart(EventScheduleItem item) {
        return time(item == null ? null : item.getStartTime());
    }
    public String scheduleEnd(EventScheduleItem item) {
        return time(item == null ? null : item.getEndTime());
    }
    public String scheduleRange(EventScheduleItem item) {
        if (item == null || item.getStartTime() == null) return "TBA";
        return item.getEndTime() == null ? scheduleStart(item)
                : scheduleStart(item) + " - " + scheduleEnd(item);
    }
    public String ticketPaid(Registration registration) {
        if (registration == null) return "Free";
        BigDecimal value = registration.getFinalPrice() == null
                ? registration.getTicketPrice() : registration.getFinalPrice();
        return freeOrMoney(value);
    }
    public String originalTicketPrice(Registration registration) {
        return freeOrMoney(registration == null ? null : registration.getTicketPrice());
    }
    public String discountAmount(Registration registration) {
        BigDecimal amount = registration == null ? null : registration.getDiscountAmount();
        return amount == null || amount.signum() <= 0 ? "৳0" : bdt(amount);
    }
    public boolean paidTicket(Registration registration) {
        return RegistrationRules.paidTicket(registration);
    }
    public boolean hasPaymentReceipt(Registration registration) {
        return paidTicket(registration) && registration.getPaymentStatus() != null
                && registration.getPaymentStatus().startsWith("PAID");
    }
    public boolean discounted(Registration registration) {
        return RegistrationRules.discounted(registration);
    }
    public String ticketName(Registration registration) {
        String name = registration == null ? null : registration.getTicketTypeName();
        return name == null || name.isBlank() ? "General Admission" : name;
    }
    public String registrationTime(Registration registration) {
        return dateTime(registration == null ? null : registration.getRegisteredAt());
    }

    public String requestType(ContactRequest request) {
        return REQUEST_TYPES.getOrDefault(request == null || request.getRequestType() == null
                ? "" : request.getRequestType(), "Other");
    }
    public String requestStatus(ContactRequest request) {
        String status = request == null || request.getStatus() == null ? "NEW" : request.getStatus();
        return switch (status) {
            case "IN_PROGRESS" -> "In Progress";
            case "RESOLVED" -> "Resolved";
            default -> "New";
        };
    }
    public String requestCreated(ContactRequest request) {
        return dateTime(request == null ? null : request.getCreatedAt());
    }
    public String requestUpdated(ContactRequest request) {
        return dateTime(request == null ? null : request.getUpdatedAt());
    }
    public String feedbackUpdated(Feedback feedback) {
        return dateTime(feedback == null ? null : feedback.getUpdatedAt());
    }
    /** "5 min ago", "3 h ago", "2 d ago" or a date; the exact time is available as a tooltip. */
    public String relativeTime(Notification notice) {
        java.time.LocalDateTime at = notice == null ? null : notice.getCreatedAt();
        if (at == null) return "";
        long minutes = java.time.Duration.between(at, java.time.LocalDateTime.now()).toMinutes();
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + " min ago";
        if (minutes < 24 * 60) return (minutes / 60) + " h ago";
        if (minutes < 7 * 24 * 60) return (minutes / (24 * 60)) + " d ago";
        return at.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
    }

    public String notificationIcon(Notification notice) {
        return com.eventflow.service.NotificationType.of(notice == null ? null : notice.getType()).icon();
    }

    public String notificationLabel(Notification notice) {
        return com.eventflow.service.NotificationType.of(notice == null ? null : notice.getType()).label();
    }

    public String notificationTime(Notification notice) {
        return dateTime(notice == null ? null : notice.getCreatedAt());
    }
    public String formattedTicketValue(BigDecimal value) {
        return value == null || value.signum() <= 0 ? "৳0" : bdt(value);
    }
    public boolean demoAccount(User user) { return userService.isDemoAccount(user); }

    private String date(LocalDate value) { return value == null ? "TBA" : value.format(DATE); }
    private String time(LocalTime value) { return value == null ? "TBA" : value.format(TIME); }
    private String dateTime(LocalDateTime value) { return value == null ? "—" : value.format(DATE_TIME); }
    private String freeOrMoney(BigDecimal value) {
        return value == null || value.signum() <= 0 ? "Free" : bdt(value);
    }
    private String bdt(BigDecimal value) { return "৳" + normalizedAmount(value); }
    private String normalizedAmount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
