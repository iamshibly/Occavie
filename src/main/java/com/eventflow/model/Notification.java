package com.eventflow.model;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "notifications")
@CompoundIndexes({
        @CompoundIndex(name = "notification_user_created", def = "{'userId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "notification_user_read", def = "{'userId': 1, 'read': 1}")
})
public class Notification {

    @Id
    private String id;
    private String userId;
    private String message;
    private String link;
    /** NotificationType name; GENERAL for older notifications. */
    @Builder.Default
    private String type = "GENERAL";
    private String eventId;
    /** Optional idempotency key: a second notification with the same key is never created. */
    @org.springframework.data.mongodb.core.index.Indexed(unique = true, sparse = true)
    private String dedupeKey;
    @Builder.Default
    private boolean read = false;
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

}
