package com.eventflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * One outgoing email, stored in MongoDB before it is sent (transactional outbox). The unique {@code dedupeKey}
 * guarantees the same logical email is queued at most once, and the stored status survives restarts and redeploys.
 *
 * <p>Status: PENDING, SENDING (leased by a worker), SENT, FAILED (gave up), SKIPPED (email disabled).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "email_outbox")
@CompoundIndex(name = "email_status_next", def = "{'status': 1, 'nextAttemptAt': 1}")
public class EmailMessage {
    @Id
    private String id;
    @Indexed(unique = true)
    private String dedupeKey;
    private String type;
    private String toEmail;
    private String toName;
    private String subject;
    private String textBody;
    private String htmlBody;
    @Builder.Default
    private String status = "PENDING";
    private int attempts;
    @Builder.Default
    private LocalDateTime nextAttemptAt = LocalDateTime.now();
    private LocalDateTime leaseUntil;
    private String lastError;
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime sentAt;
}
