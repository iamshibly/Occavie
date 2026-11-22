package com.eventflow.service;

import com.eventflow.dto.EventDraftForm;
import com.eventflow.dto.EventForm;
import com.eventflow.model.Event;
import com.eventflow.model.EventImage;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.repository.EventImageRepository;
import com.eventflow.repository.EventRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> EVENT_CATEGORIES = Set.of(
            "Technology", "Business", "Education", "Music", "Career", "Sports", "Community", "Arts", "Science", "Food", "Charity", "Other", "Health", "Design"
    );
    private static final long MAX_IMAGE_SIZE = 2 * 1024 * 1024;
    private static final String DEFAULT_IMAGE = "/images/event-visual.png";

    private final EventRepository eventRepository;
    private final EventImageRepository eventImageRepository;
    private final RegistrationService registrationService;
    private final NotificationService notificationService;
    private final EventProgramService eventProgramService;
    private final TicketTypeService ticketTypeService;
    private final CouponService couponService;
    private final EventGalleryService eventGalleryService;
    private final WaitlistService waitlistService;

    /** When true, organizers submit events for administrator approval instead of publishing directly. */
    @org.springframework.beans.factory.annotation.Value("${occavie.approval.required:false}")
    private boolean approvalRequired = false;



    @Caching(evict = {
            @CacheEvict(value = "eventsAll", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "featuredEvents", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "eventsByOrganizer", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "eventCount", allEntries = true, beforeInvocation = true)
    })
    public Event create(EventForm form, User organizer) {
        validate(form, 0L, null);
        Event event = Event.builder()
                .organizerId(organizer.getId())
                .organizerName(organizer.getFullName())
                .imageUrl(DEFAULT_IMAGE)
                .build();
        applyFields(event, form);
        event = eventRepository.save(event);
        saveImage(event, form.image());
        return event;
    }

    @Caching(evict = {
            @CacheEvict(value = "eventsAll", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "featuredEvents", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "eventsByOrganizer", allEntries = true, beforeInvocation = true)
    })
    public Event updateOwned(String eventId, EventForm form, User organizer) {
        Event event = findOwned(eventId, organizer);
        requireEditable(event);
        String change = update(event, form);
        if (change != null) notifyParticipants(event, change);
        return event;
    }

    @Caching(evict = {
            @CacheEvict(value = "eventsAll", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "featuredEvents", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "eventsByOrganizer", allEntries = true, beforeInvocation = true)
    })
    public Event updateAsAdmin(String eventId, EventForm form) {
        Event event = findById(eventId);
        requireEditable(event);
        String change = update(event, form);
        if (change != null) notifyParticipants(event, change + " (updated by an administrator)");
        return event;
    }

    @Caching(evict = {
            @CacheEvict(value = "eventsAll", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "featuredEvents", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "eventsByOrganizer", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "eventCount", allEntries = true, beforeInvocation = true)
    })
    public void deleteOwned(String eventId, User organizer) {
        Event event = findOwned(eventId, organizer);
        requireEditable(event);
        notifyParticipants(event, event.getTitle() + " has been cancelled by the organizer.");
        registrationService.deleteAllByEvent(eventId);
        waitlistService.deleteAllByEvent(eventId);
        eventProgramService.deleteAllByEvent(eventId);
        ticketTypeService.deleteAllByEvent(eventId);
        couponService.deleteAllByEvent(eventId);
        eventGalleryService.deleteAllByEvent(eventId);
        eventImageRepository.deleteByEventId(eventId);
        eventRepository.delete(event);
    }

    @Caching(evict = {
            @CacheEvict(value = "eventsAll", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "featuredEvents", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "eventsByOrganizer", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "eventCount", allEntries = true, beforeInvocation = true)
    })
    public void deleteAsAdmin(String eventId) {
        Event event = findById(eventId);
        requireEditable(event);
        notifyParticipants(event, event.getTitle() + " was cancelled by an administrator.");
        registrationService.deleteAllByEvent(eventId);
        waitlistService.deleteAllByEvent(eventId);
        eventProgramService.deleteAllByEvent(eventId);
        ticketTypeService.deleteAllByEvent(eventId);
        couponService.deleteAllByEvent(eventId);
        eventGalleryService.deleteAllByEvent(eventId);
        eventImageRepository.deleteByEventId(eventId);
        eventRepository.delete(event);
    }

    public Event findById(String id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found."));
    }

    public Optional<Event> findOptional(String id) {
        return eventRepository.findById(id);
    }

    public Optional<EventImage> findImage(String eventId) {
        return eventImageRepository.findByEventId(eventId);
    }

    public Event findOwned(String id, User organizer) {
        Event event = findById(id);
        if (event.getOrganizerId() == null || !event.getOrganizerId().equals(organizer.getId())) {
            throw new IllegalArgumentException("You cannot manage this event.");
        }
        return event;
    }

    @Cacheable("eventsAll")
    public List<Event> findAllSorted() {
        return eventRepository.findAllByOrderByDateAsc();
    }

    @Cacheable("featuredEvents")
    public List<Event> findFeatured() {
        return eventRepository.findTop3ByFeaturedTrueOrderByDateAsc();
    }

    @Cacheable(value = "eventsByOrganizer", key = "#organizerId")
    public List<Event> findByOrganizer(String organizerId) {
        return eventRepository.findByOrganizerIdOrderByDateAsc(organizerId);
    }

    public List<Event> findCompletedAll() {
        return completedNewestFirst(findAllSorted());
    }

    public List<Event> findCompletedByOrganizer(String organizerId) {
        return completedNewestFirst(findByOrganizer(organizerId));
    }

    public boolean isCompleted(Event event) {
        return event != null && "COMPLETED".equals(EventRules.status(event));
    }

    public void requireEditable(Event event) {
        if (EventRules.isCancelled(event)) {
            throw new IllegalArgumentException("Cancelled events are read-only. Duplicate the event to run it again.");
        }
        if (isCompleted(event)) {
            throw new IllegalArgumentException("Completed events are read-only and cannot be changed.");
        }
    }

    private List<Event> completedNewestFirst(List<Event> events) {
        return events.stream()
                .filter(this::isCompleted)
                .sorted(Comparator
                        .comparing(Event::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Event::getTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Cacheable("eventCount")
    public long count() {
        return eventRepository.count();
    }

    public Map<String, Long> registrationCounts(List<Event> events) {
        if (events == null || events.isEmpty()) return Map.of();
        return registrationService.countsForEvents(events.stream().map(Event::getId).toList());
    }

    public boolean isUpcoming(Event event) {
        if (event.getDate() == null || !EventRules.isPublished(event) || EventRules.isCancelled(event)) {
            return false;
        }
        LocalDate today = LocalDate.now();
        if (event.getDate().isAfter(today)) {
            return true;
        }
        if (event.getDate().isBefore(today)) {
            return false;
        }
        return event.getTime() == null
                || !event.getTime().isBefore(LocalTime.now().withSecond(0).withNano(0));
    }

    /** Applies an edit and returns a participant-facing message when something major changed (date, time, venue, price). */
    private String update(Event event, EventForm form) {
        long currentRegistrations = registrationService.countByEvent(event.getId());
        validate(form, currentRegistrations, event.getId());
        LocalDate oldDate = event.getDate();
        LocalTime oldTime = event.getTime();
        String oldLocation = event.getLocation();
        BigDecimal oldPrice = event.getBasePrice();
        int oldCapacity = event.getCapacity();
        applyFields(event, form);
        eventRepository.save(event);
        ticketTypeService.syncPricing(event);
        if (!EventRules.isPaid(event)) couponService.pauseAllByEvent(event.getId());
        saveImage(event, form.image());
        if (event.getCapacity() > oldCapacity) waitlistService.promote(event.getId());
        return changeMessage(event, oldDate, oldTime, oldLocation, oldPrice);
    }

    static String changeMessage(Event event, LocalDate oldDate, LocalTime oldTime, String oldLocation, BigDecimal oldPrice) {
        boolean when = !java.util.Objects.equals(oldDate, event.getDate()) || !java.util.Objects.equals(oldTime, event.getTime());
        boolean where = oldLocation != null && !oldLocation.equals(event.getLocation());
        boolean price = oldPrice != null && event.getBasePrice() != null && oldPrice.compareTo(event.getBasePrice()) != 0;
        if (!when && !where && !price) return null;
        StringBuilder message = new StringBuilder(event.getTitle()).append(": ");
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (when) parts.add("rescheduled to " + event.getDate() + (event.getTime() == null ? "" : " at " + event.getTime()));
        if (where) parts.add("the venue is now " + event.getLocation());
        if (price) parts.add("the ticket price changed to " + event.getBasePrice().stripTrailingZeros().toPlainString());
        return message.append(String.join("; ", parts)).append(". Please review the latest details.").toString();
    }

    // ------------------------------------------------------------------ drafts, publishing, duplication, cancellation

    /** Step 1 of the wizard: a private draft that only its organizer (and admins) can see. */
    @EvictEventCaches
    public Event createDraft(EventDraftForm form, User organizer) {
        if (blank(form.title()) || blank(form.category()) || blank(form.capacity())) {
            throw new IllegalArgumentException("Enter a title, category and capacity to start your draft.");
        }
        Event draft = Event.builder().organizerId(organizer.getId()).organizerName(organizer.getFullName())
                .imageUrl(DEFAULT_IMAGE).status("DRAFT").capacity(1).build();
        applyDraft(draft, form);
        draft = eventRepository.save(draft);
        saveImage(draft, form.image());
        return draft;
    }

    /** Saves whatever the organizer filled in for a wizard step. Drafts only; published events use the edit form. */
    @EvictEventCaches
    public Event updateDraft(String eventId, EventDraftForm form, User organizer) {
        Event event = findOwned(eventId, organizer);
        requireDraft(event);
        applyDraft(event, form);
        eventRepository.save(event);
        saveImage(event, form.image());
        return event;
    }

    /** What still blocks publishing. Empty means ready. */
    public List<String> readiness(Event event) {
        List<String> missing = new java.util.ArrayList<>();
        if (event.getTitle() == null || event.getTitle().trim().length() < 3) missing.add("Title");
        if (event.getCategory() == null || !EVENT_CATEGORIES.contains(event.getCategory())) missing.add("Category");
        if (event.getCapacity() < 1) missing.add("Capacity");
        if (event.getDate() == null || event.getTime() == null) {
            missing.add("Date and time");
        } else if (event.getDate().isBefore(LocalDate.now())
                || (event.getDate().isEqual(LocalDate.now()) && event.getTime().isBefore(LocalTime.now().withSecond(0).withNano(0)))) {
            missing.add("A date and time in the future");
        }
        if (event.getLocation() == null || event.getLocation().trim().length() < 3) missing.add("Venue");
        if (event.getDescription() == null || event.getDescription().trim().length() < 20) missing.add("Description (20+ characters)");
        if (EventRules.isPaid(event) && (event.getBasePrice() == null || event.getBasePrice().compareTo(BigDecimal.ZERO) <= 0)) {
            missing.add("A ticket price above zero");
        }
        return missing;
    }

    @EvictEventCaches
    public Event publish(String eventId, User organizer) {
        if (approvalRequired) {
            throw new IllegalArgumentException("Events need administrator approval before they go live. Submit this event for approval.");
        }
        Event event = findOwned(eventId, organizer);
        requireDraft(event);
        List<String> missing = readiness(event);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Complete before publishing: " + String.join(", ", missing) + ".");
        }
        event.setStatus("UPCOMING");
        return eventRepository.save(event);
    }

    /** A new private draft with the same details, ticket types and program. Date and time must be chosen again. */
    @EvictEventCaches
    public Event duplicate(String eventId, User organizer) {
        Event source = findOwned(eventId, organizer);
        String title = "Copy of " + (source.getTitle() == null ? "event" : source.getTitle());
        Event copy = eventRepository.save(Event.builder()
                .organizerId(organizer.getId()).organizerName(organizer.getFullName())
                .title(title.length() > 80 ? title.substring(0, 80) : title)
                .category(source.getCategory()).location(source.getLocation()).description(source.getDescription())
                .capacity(source.getCapacity()).pricingType(source.getPricingType()).basePrice(source.getBasePrice())
                .address(source.getAddress()).onlineUrl(source.getOnlineUrl())
                .imageUrl(DEFAULT_IMAGE).status("DRAFT").build());
        eventImageRepository.findByEventId(source.getId()).ifPresent(image -> {
            EventImage stored = eventImageRepository.save(EventImage.builder().eventId(copy.getId())
                    .contentType(image.getContentType()).data(image.getData()).build());
            copy.setImageUrl("/event-images/" + copy.getId() + "?v=" + stored.getId());
            eventRepository.save(copy);
        });
        ticketTypeService.copyAll(source.getId(), copy.getId());
        eventProgramService.copyProgram(source.getId(), copy.getId());
        return copy;
    }

    /** Cancels a published, upcoming event (kept as history), tells registrants and the waitlist. */
    @EvictEventCaches
    public Event cancelOwned(String eventId, String reason, User organizer) {
        return cancel(findOwned(eventId, organizer), reason, "the organizer");
    }

    @EvictEventCaches
    public Event cancelAsAdmin(String eventId, String reason) {
        return cancel(findById(eventId), reason, "an administrator");
    }

    private Event cancel(Event event, String reason, String by) {
        if (EventRules.isCancelled(event)) throw new IllegalArgumentException("This event is already cancelled.");
        if (!EventRules.isPublished(event)) throw new IllegalArgumentException("This event is not published. Delete it instead.");
        if (isCompleted(event)) throw new IllegalArgumentException("Completed events cannot be cancelled.");
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.length() > 200) throw new IllegalArgumentException("Keep the cancellation reason under 200 characters.");
        event.setStatus("CANCELLED");
        event.setCancellationReason(cleanReason.isEmpty() ? null : cleanReason);
        eventRepository.save(event);
        couponService.pauseAllByEvent(event.getId());
        String message = event.getTitle() + " has been cancelled by " + by + "." + (cleanReason.isEmpty() ? "" : " Reason: " + cleanReason);
        notifyParticipants(event, message);
        waitlistService.cancelAll(event.getId(), message);
        return event;
    }


    // ------------------------------------------------------------------ approval workflow

    public boolean isApprovalRequired() {
        return approvalRequired;
    }

    /** DRAFT/REJECTED -> PENDING_APPROVAL. The organizer must own the event and everything required must be filled in. */
    @EvictEventCaches
    public Event submitForApproval(String eventId, User organizer) {
        if (!approvalRequired) throw new IllegalArgumentException("Approval is not enabled. Publish the event directly.");
        Event event = findOwned(eventId, organizer);
        requireDraft(event);
        List<String> missing = readiness(event);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Complete before submitting: " + String.join(", ", missing) + ".");
        event.setStatus("PENDING_APPROVAL");
        event.setSubmittedAt(java.time.LocalDateTime.now());
        event.setApprovalNote(null);
        return eventRepository.save(event);
    }

    /** PENDING_APPROVAL -> DRAFT: the organizer takes it back to make changes. */
    @EvictEventCaches
    public Event withdraw(String eventId, User organizer) {
        Event event = findOwned(eventId, organizer);
        if (!"PENDING_APPROVAL".equals(event.getStatus())) throw new IllegalArgumentException("Only events awaiting approval can be withdrawn.");
        event.setStatus("DRAFT");
        return eventRepository.save(event);
    }

    /** PENDING_APPROVAL -> UPCOMING (published). Administrators only. */
    @EvictEventCaches
    public Event approve(String eventId, User admin) {
        requireAdmin(admin);
        Event event = findById(eventId);
        if (!"PENDING_APPROVAL".equals(event.getStatus())) throw new IllegalArgumentException("This event is not awaiting approval.");
        event.setStatus("UPCOMING");
        event.setReviewedAt(java.time.LocalDateTime.now());
        event.setReviewedBy(admin.getId());
        event.setApprovalNote(null);
        eventRepository.save(event);
        notificationService.notify(NotificationType.APPROVAL_UPDATE, event.getOrganizerId(), event.getId(), event.getTitle(),
                event.getTitle() + " was approved and is now published.", "/events/" + event.getId(),
                "APPROVED:" + event.getId() + ":" + event.getReviewedAt(), List.of("Participants can now find and register for it."));
        return event;
    }

    /** PENDING_APPROVAL -> REJECTED with a reason the organizer can act on. Administrators only. */
    @EvictEventCaches
    public Event reject(String eventId, String reason, User admin) {
        requireAdmin(admin);
        String note = reason == null ? "" : reason.trim();
        if (note.length() < 5 || note.length() > 300) throw new IllegalArgumentException("Give a reason between 5 and 300 characters.");
        Event event = findById(eventId);
        if (!"PENDING_APPROVAL".equals(event.getStatus())) throw new IllegalArgumentException("This event is not awaiting approval.");
        event.setStatus("REJECTED");
        event.setApprovalNote(note);
        event.setReviewedAt(java.time.LocalDateTime.now());
        event.setReviewedBy(admin.getId());
        eventRepository.save(event);
        notificationService.notify(NotificationType.APPROVAL_UPDATE, event.getOrganizerId(), event.getId(), event.getTitle(),
                event.getTitle() + " was not approved. Reason: " + note, "/organizer/events",
                "REJECTED:" + event.getId() + ":" + event.getReviewedAt(), List.of("Reason: " + note, "Edit the event and submit it again when ready."));
        return event;
    }

    public List<Event> pendingApprovals() {
        return eventRepository.findByStatusOrderBySubmittedAtAsc("PENDING_APPROVAL");
    }

    public long pendingApprovalCount() {
        return eventRepository.countByStatus("PENDING_APPROVAL");
    }

    private static void requireAdmin(User user) {
        if (user == null || !"ADMIN".equals(user.getRole())) throw new IllegalArgumentException("Only administrators can review events.");
    }

    private void requireDraft(Event event) {
        if (!EventRules.isEditableDraft(event)) {
            throw new IllegalArgumentException("Only drafts can be edited step by step. Use Edit for published events.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Validates and applies only the fields present in the form. */
    private void applyDraft(Event event, EventDraftForm form) {
        if (!blank(form.title())) {
            String title = form.title().trim();
            if (title.length() < 3 || title.length() > 80) throw new IllegalArgumentException("Event title must be between 3 and 80 characters.");
            event.setTitle(title);
        }
        if (!blank(form.category())) {
            if (!EVENT_CATEGORIES.contains(form.category().trim())) throw new IllegalArgumentException("Choose a valid event category.");
            event.setCategory(form.category().trim());
        }
        if (!blank(form.capacity())) {
            int capacity;
            try { capacity = Integer.parseInt(form.capacity().trim()); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Capacity must be a whole number."); }
            if (capacity < 1 || capacity > 100000) throw new IllegalArgumentException("Capacity must be between 1 and 100,000.");
            if (event.getId() != null && capacity < ticketTypeService.totalAllocatedCapacity(event.getId())) {
                throw new IllegalArgumentException("Capacity cannot be lower than the total capacity allocated to ticket types.");
            }
            event.setCapacity(capacity);
        }
        if (!blank(form.date())) {
            LocalDate date;
            try { date = LocalDate.parse(form.date().trim()); }
            catch (Exception ex) { throw new IllegalArgumentException("Enter a valid event date."); }
            if (date.isBefore(LocalDate.now())) throw new IllegalArgumentException("Event date cannot be in the past.");
            event.setDate(date);
        }
        if (!blank(form.time())) {
            try { event.setTime(LocalTime.parse(form.time().trim())); }
            catch (Exception ex) { throw new IllegalArgumentException("Enter a valid start time."); }
        }
        if (event.getDate() != null && event.getTime() != null && event.getDate().isEqual(LocalDate.now())
                && event.getTime().isBefore(LocalTime.now().withSecond(0).withNano(0))) {
            throw new IllegalArgumentException("Event date and time cannot be in the past.");
        }
        if (!blank(form.location())) {
            String location = form.location().trim();
            if (location.length() < 3 || location.length() > 100) throw new IllegalArgumentException("Location must be between 3 and 100 characters.");
            event.setLocation(location);
        }
        if (!blank(form.description())) {
            String description = form.description().trim();
            if (description.length() > 1000) throw new IllegalArgumentException("Description must be 1000 characters or fewer.");
            event.setDescription(description);
        }
        if (!blank(form.pricingType())) {
            String pricing = form.pricingType().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("FREE", "PAID").contains(pricing)) throw new IllegalArgumentException("Choose whether the event is free or paid.");
            event.setPricingType(pricing);
            if ("FREE".equals(pricing)) event.setBasePrice(BigDecimal.ZERO);
        }
        if (!blank(form.basePrice()) && EventRules.isPaid(event)) {
            BigDecimal price;
            try { price = new BigDecimal(form.basePrice().trim()).setScale(2, RoundingMode.HALF_UP); }
            catch (Exception ex) { throw new IllegalArgumentException("Enter a valid base ticket price."); }
            if (price.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(new BigDecimal("10000000")) > 0) {
                throw new IllegalArgumentException("Paid event price must be greater than 0.");
            }
            event.setBasePrice(price);
        }
        validateVenueExtras(form.address(), form.onlineUrl());
        applyVenueExtras(event, form.address(), form.onlineUrl());
        validateImage(form.image());
    }

    private void validateImage(MultipartFile image) {
        if (image != null && !image.isEmpty()) {
            if (image.getSize() > MAX_IMAGE_SIZE) throw new IllegalArgumentException("Event image must be 2 MB or smaller.");
            String type = image.getContentType() == null ? "" : image.getContentType().toLowerCase(Locale.ROOT);
            if (!IMAGE_TYPES.contains(type)) throw new IllegalArgumentException("Event image must be JPG, PNG or WEBP.");
            ImageUpload.verify(image, "Event image");
        }
    }

    private void validate(EventForm form, long currentRegistrations, String eventId) {
        String title = form.title();
        String category = form.category();
        String location = form.location();
        String description = form.description();

        if (title == null || title.trim().length() < 3 || title.trim().length() > 80) {
            throw new IllegalArgumentException("Event title must be between 3 and 80 characters.");
        }
        if (category == null || !EVENT_CATEGORIES.contains(category.trim())) {
            throw new IllegalArgumentException("Choose a valid event category.");
        }
        if (location == null || location.trim().length() < 3 || location.trim().length() > 100) {
            throw new IllegalArgumentException("Location must be between 3 and 100 characters.");
        }
        if (description == null || description.trim().length() < 20 || description.trim().length() > 1000) {
            throw new IllegalArgumentException("Description must be between 20 and 1000 characters.");
        }
        if (form.capacity() < 1 || form.capacity() > 100000) {
            throw new IllegalArgumentException("Capacity must be greater than 0.");
        }
        if (form.capacity() < currentRegistrations) {
            throw new IllegalArgumentException("Capacity cannot be lower than the current number of registrations.");
        }
        if (eventId != null && form.capacity() < ticketTypeService.totalAllocatedCapacity(eventId)) {
            throw new IllegalArgumentException("Capacity cannot be lower than the total capacity allocated to ticket types.");
        }

        String pricingType = form.pricingType() == null ? "FREE" : form.pricingType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("FREE", "PAID").contains(pricingType)) {
            throw new IllegalArgumentException("Choose whether the event is free or paid.");
        }
        if ("PAID".equals(pricingType)) {
            BigDecimal basePrice;
            try {
                basePrice = new BigDecimal(form.basePrice() == null ? "" : form.basePrice().trim()).setScale(2, RoundingMode.HALF_UP);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Enter a valid base ticket price.");
            }
            if (basePrice.compareTo(BigDecimal.ZERO) <= 0 || basePrice.compareTo(new BigDecimal("10000000")) > 0) {
                throw new IllegalArgumentException("Paid event price must be greater than 0.");
            }
        }

        LocalDate parsedDate;
        LocalTime parsedTime;
        try {
            parsedDate = LocalDate.parse(form.date());
            parsedTime = LocalTime.parse(form.time());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Enter a valid event date and time.");
        }

        LocalDate today = LocalDate.now();
        if (parsedDate.isBefore(today)
                || (parsedDate.isEqual(today)
                && parsedTime.isBefore(LocalTime.now().withSecond(0).withNano(0)))) {
            throw new IllegalArgumentException("Event date and time cannot be in the past.");
        }

        validateVenueExtras(form.address(), form.onlineUrl());

        MultipartFile image = form.image();
        if (image != null && !image.isEmpty()) {
            if (image.getSize() > MAX_IMAGE_SIZE) {
                throw new IllegalArgumentException("Event image must be 2 MB or smaller.");
            }
            String type = image.getContentType() == null ? "" : image.getContentType().toLowerCase(Locale.ROOT);
            if (!IMAGE_TYPES.contains(type)) {
                throw new IllegalArgumentException("Event image must be JPG, PNG or WEBP.");
            }
            ImageUpload.verify(image, "Event image");
        }
    }

    private void applyFields(Event event, EventForm form) {
        event.setTitle(form.title().trim());
        event.setCategory(form.category().trim());
        event.setLocation(form.location().trim());
        event.setDate(LocalDate.parse(form.date()));
        event.setTime(LocalTime.parse(form.time()));
        event.setDescription(form.description().trim());
        event.setCapacity(form.capacity());
        String pricingType = form.pricingType() == null ? "FREE" : form.pricingType().trim().toUpperCase(Locale.ROOT);
        event.setPricingType("PAID".equals(pricingType) ? "PAID" : "FREE");
        if (EventRules.isPaid(event)) {
            event.setBasePrice(new BigDecimal(form.basePrice().trim()).setScale(2, RoundingMode.HALF_UP));
        } else {
            event.setBasePrice(BigDecimal.ZERO);
        }
        applyVenueExtras(event, form.address(), form.onlineUrl());
        event.setStatus("UPCOMING");
    }

    /** Optional street address (max 160 chars) and online link (https only, max 300 chars). */
    static void validateVenueExtras(String address, String onlineUrl) {
        if (address != null && address.trim().length() > 160) throw new IllegalArgumentException("Address must be 160 characters or fewer.");
        if (onlineUrl != null && !onlineUrl.isBlank()) {
            String url = onlineUrl.trim();
            if (url.length() > 300) throw new IllegalArgumentException("Online link is too long.");
            try {
                java.net.URI uri = java.net.URI.create(url);
                if (uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Online link must be a valid https:// address.");
            }
        }
    }

    private static void applyVenueExtras(Event event, String address, String onlineUrl) {
        if (address != null) event.setAddress(address.isBlank() ? null : address.trim());
        if (onlineUrl != null) event.setOnlineUrl(onlineUrl.isBlank() ? null : onlineUrl.trim());
    }

    private void saveImage(Event event, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return;
        }

        try {
            String type = image.getContentType() == null
                    ? "image/jpeg"
                    : image.getContentType().toLowerCase(Locale.ROOT);
            eventImageRepository.deleteByEventId(event.getId());
            EventImage storedImage = eventImageRepository.save(EventImage.builder().eventId(event.getId()).contentType(type).data(image.getBytes()).build());
            event.setImageUrl("/event-images/" + event.getId() + "?v=" + storedImage.getId());
            eventRepository.save(event);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not save the event image. Try again.");
        }
    }

    /** Tells every registered participant about a major change or cancellation (in-app and, where allowed, email). */
    private void notifyParticipants(Event event, String message) {
        boolean cancelled = EventRules.isCancelled(event) || (message != null && message.toLowerCase(Locale.ROOT).contains("cancelled"));
        NotificationType type = cancelled ? NotificationType.EVENT_CANCELLED : NotificationType.EVENT_RESCHEDULED;
        String link = cancelled ? "/participant/registrations" : "/events/" + event.getId();
        // The key includes the content, so repeating the same change never notifies twice but a new change does.
        String version = Integer.toHexString((message == null ? "" : message).hashCode());
        for (Registration registration : registrationService.findByEvent(event.getId())) {
            notificationService.notify(type, registration.getParticipantId(), event.getId(), event.getTitle(), message, link,
                    (cancelled ? "EVTCANCEL:" : "EVTCHANGE:") + event.getId() + ":" + (cancelled ? "" : version + ":") + registration.getParticipantId(),
                    cancelled ? null : List.of("Please review the latest event details."));
        }
    }
}
