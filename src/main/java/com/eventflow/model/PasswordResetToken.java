package com.eventflow.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A single-use password reset token. Only the SHA-256 hash of the token is stored, so a database leak does not
 * allow resets. MongoDB removes expired rows automatically (TTL index on {@code expiresAt}).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "password_reset_tokens")
public class PasswordResetToken {
    @Id
    private String id;
    @Indexed
    private String userId;
    @Indexed(unique = true)
    private String tokenHash;
    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;
    private Instant usedAt;
    @Builder.Default
    private Instant createdAt = Instant.now();
}
