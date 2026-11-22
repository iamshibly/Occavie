package com.eventflow.model;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalTime;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "events")
@CompoundIndex(name = "events_organizer_date", def = "{'organizerId': 1, 'date': 1}")
public class Event {

    @Id
    private String id;

    private String title;

    @Indexed
    private String category;

    private String location;

    @Indexed
    private LocalDate date;

    private LocalTime time;
    private String description;
    private int capacity;
    private String status;
    private boolean featured;

    @Indexed
    private String organizerId;

    private String organizerName;
    @Builder.Default
    private String imageUrl = "/images/event-visual.png";
    @Builder.Default
    private String pricingType = "FREE";
    @Builder.Default
    private BigDecimal basePrice = BigDecimal.ZERO;
    /** Internal write-conflict guard for atomic ticket reservations in MongoDB. */
    private long bookingVersion;

    /** Registrations that were cancelled or removed. Cancelled registrations are deleted, so this counter keeps the history for analytics. */
    private long cancelledRegistrations;

    /** Set when an event is cancelled (status CANCELLED); registrations are kept as history. */
    private String cancellationReason;

    /** Approval workflow (only used when occavie.approval.required=true). */
    private String approvalNote;
    private java.time.LocalDateTime submittedAt;
    private java.time.LocalDateTime reviewedAt;
    private String reviewedBy;

    /** Optional street address shown next to the venue name; used for the map link. */
    private String address;
    /** Optional https link for online or hybrid events; only revealed to registered attendees. */
    private String onlineUrl;


}
