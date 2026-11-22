package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.service.CsvExport;
import com.eventflow.service.EventService;
import com.eventflow.service.RegistrationService;
import com.eventflow.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CSV exports for an organiser's own events (administrators may export any event).
 * Ownership is enforced by {@link EventService#findOwned}; participant data is limited to what an organiser needs
 * to run the event (name, email, ticket, status) and never includes payment references or ticket secrets.
 */
@RestController
@RequiredArgsConstructor
public class ExportController {
    private final EventService eventService;
    private final RegistrationService registrationService;
    private final UserService userService;

    @GetMapping("/organizer/events/{id}/export/{kind}.csv")
    public ResponseEntity<byte[]> export(@PathVariable String id, @PathVariable String kind, Authentication authentication) {
        User user = userService.currentUser(authentication);
        Event event;
        try {
            event = eventService.findOwned(id, user);            // fails when the event is missing or not owned by the caller
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).build();   // same answer for both, no probing
        }
        List<Registration> registrations = registrationService.findByEvent(id);
        StringBuilder csv = new StringBuilder();
        switch (kind) {
            case "participants" -> {
                csv.append(CsvExport.row(List.of("Name", "Email", "Ticket type", "Registered at", "Booking status")));
                for (Registration r : registrations) csv.append(CsvExport.row(List.of(n(r.getParticipantName()), n(r.getParticipantEmail()),
                        n(r.getTicketTypeName()), n(r.getRegisteredAt()), status(r))));
            }
            case "bookings" -> {
                csv.append(CsvExport.row(List.of("Booking ID", "Name", "Ticket type", "Coupon", "Amount (simulated demo value)", "Payment status", "Booking status", "Registered at")));
                for (Registration r : registrations) csv.append(CsvExport.row(List.of(n(r.getId()), n(r.getParticipantName()), n(r.getTicketTypeName()),
                        n(r.getCouponCode()), r.getFinalPrice() == null ? "0" : r.getFinalPrice().toPlainString(), n(r.getPaymentStatus()), status(r), n(r.getRegisteredAt()))));
            }
            case "checkin" -> {
                csv.append(CsvExport.row(List.of("Name", "Email", "Ticket type", "Checked in", "Checked in at")));
                for (Registration r : registrations) csv.append(CsvExport.row(List.of(n(r.getParticipantName()), n(r.getParticipantEmail()), n(r.getTicketTypeName()),
                        r.getCheckedInAt() != null ? "Yes" : "No", n(r.getCheckedInAt()))));
            }
            case "attendance" -> {
                long in = registrations.stream().filter(r -> r.getCheckedInAt() != null).count();
                csv.append(CsvExport.row(List.of("Event", "Date", "Capacity", "Registered", "Checked in", "Attendance %", "Cancelled registrations")));
                csv.append(CsvExport.row(List.of(n(event.getTitle()), n(event.getDate()), event.getCapacity(), registrations.size(), in,
                        registrations.isEmpty() ? 0 : Math.round(in * 100.0 / registrations.size()), event.getCancelledRegistrations())));
            }
            default -> { return ResponseEntity.notFound().build(); }
        }
        String file = "occavie-" + kind + "-" + id.replaceAll("[^A-Za-z0-9]", "") + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(CsvExport.bytes(csv.toString()));
    }

    private static String status(Registration r) {
        return r.getCheckedInAt() != null ? "Checked in" : "Confirmed";
    }

    private static Object n(Object v) { return v == null ? "" : v; }
}
