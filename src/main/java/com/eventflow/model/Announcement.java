package com.eventflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** An organizer message to the registered attendees of one of their own events (kept for audit and rate limits). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "announcements")
@CompoundIndex(name = "announcement_event_created", def = "{'eventId': 1, 'createdAt': -1}")
public class Announcement {
    @Id
    private String id;
    private String eventId;
    private String organizerId;
    private String subject;
    private String message;
    private int recipientCount;
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
