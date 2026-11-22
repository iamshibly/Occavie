package com.eventflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * One row per participant per event, so nobody can join a waitlist twice (unique index). The position is never
 * stored: it is derived from {@code joinedAt} (then id) among WAITING rows, so it cannot drift.
 *
 * <p>Status: WAITING, OFFERED (a seat is reserved until {@code offerExpiresAt}), CLAIMED (registered),
 * EXPIRED (offer lapsed), LEFT (left voluntarily) or CANCELLED (event cancelled).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "waitlist_entries")
@CompoundIndexes({
        @CompoundIndex(name = "waitlist_event_participant_unique", def = "{'eventId': 1, 'participantId': 1}", unique = true),
        @CompoundIndex(name = "waitlist_event_status_joined", def = "{'eventId': 1, 'status': 1, 'joinedAt': 1}"),
        @CompoundIndex(name = "waitlist_status_offer_expiry", def = "{'status': 1, 'offerExpiresAt': 1}")
})
public class WaitlistEntry {
    @Id
    private String id;
    private String eventId;
    private String participantId;
    private String participantName;
    private String participantEmail;
    @Builder.Default
    private String status = "WAITING";
    @Builder.Default
    private LocalDateTime joinedAt = LocalDateTime.now();
    private LocalDateTime offeredAt;
    private LocalDateTime offerExpiresAt;

    public boolean isActive() { return "WAITING".equals(status) || "OFFERED".equals(status); }
}
