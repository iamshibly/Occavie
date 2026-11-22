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

/** A participant's bookmark. The unique index makes duplicates impossible even under concurrent clicks. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "saved_events")
@CompoundIndexes({
        @CompoundIndex(name = "saved_participant_event_unique", def = "{'participantId': 1, 'eventId': 1}", unique = true),
        @CompoundIndex(name = "saved_participant_time", def = "{'participantId': 1, 'savedAt': -1}")
})
public class SavedEvent {
    @Id
    private String id;
    private String participantId;
    private String eventId;
    @Builder.Default
    private LocalDateTime savedAt = LocalDateTime.now();
}
