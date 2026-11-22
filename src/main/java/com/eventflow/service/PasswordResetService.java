package com.eventflow.service;

import com.eventflow.model.PasswordResetToken;
import com.eventflow.model.User;
import com.eventflow.repository.PasswordResetTokenRepository;
import com.eventflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Email-link password reset. Whoever requests a reset must be able to read the account's mailbox: the token is sent
 * only by email, is random (256 bits), stored hashed, expires after 30 minutes and works once. Requests always look
 * the same to the caller, so the form cannot be used to discover which emails have accounts.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {
    static final Duration VALIDITY = Duration.ofMinutes(30);

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final EmailOutboxService emailOutbox;
    private final EmailProvider emailProvider;
    private final MongoTemplate mongoTemplate;

    @Value("${occavie.public-base-url:http://localhost:8080}")
    private String publicBaseUrl = "http://localhost:8080";

    private final SecureRandom random = new SecureRandom();

    /** Resetting is only possible when reset emails can actually be delivered. */
    public boolean isAvailable() {
        return emailProvider.enabled();
    }

    /** Queues a reset email for an existing, enabled, non-demo account. Silently does nothing otherwise. */
    public void request(String email) {
        if (!isAvailable() || email == null || email.isBlank()) return;
        Optional<User> found = userRepository.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT));
        if (found.isEmpty() || !found.get().isEnabled() || userService.isDemoAccount(found.get())) return;
        User user = found.get();
        tokenRepository.deleteByUserIdAndUsedAtIsNull(user.getId());   // only the newest link works
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256(token);
        tokenRepository.save(PasswordResetToken.builder().userId(user.getId()).tokenHash(hash)
                .expiresAt(Instant.now().plus(VALIDITY)).build());
        String link = publicBaseUrl.replaceAll("/+$", "") + "/reset-password?token=" + token;
        EmailTemplates.Rendered mail = EmailTemplates.render(NotificationType.PASSWORD_RESET, user.getFullName(), null,
                "We received a request to reset your Occavie password. The link below works once and expires in 30 minutes. "
                        + "If you did not ask for this, you can ignore this email: your password stays the same.",
                link, List.of());
        emailOutbox.enqueue("PASSWORD_RESET", "reset:" + hash, user.getEmail(), user.getFullName(), mail.subject(), mail.text(), mail.html());
    }

    /** True while the token exists, is unused and has not expired (used to decide whether to show the form). */
    public boolean isValid(String token) {
        if (token == null || token.length() < 20) return false;
        return tokenRepository.findByTokenHash(sha256(token))
                .filter(t -> t.getUsedAt() == null && t.getExpiresAt().isAfter(Instant.now())).isPresent();
    }

    /** Sets the new password and burns the token in one atomic step. */
    public void reset(String token, String newPassword, String confirmPassword) {
        userService.validateNewPassword(newPassword, confirmPassword);   // do not burn the token on a weak password
        if (token == null || token.length() < 20) throw new IllegalArgumentException("This reset link is invalid or has expired.");
        PasswordResetToken consumed = mongoTemplate.findAndModify(
                Query.query(Criteria.where("tokenHash").is(sha256(token)).and("usedAt").is(null).and("expiresAt").gt(Instant.now())),
                new Update().set("usedAt", Instant.now()), FindAndModifyOptions.options().returnNew(true), PasswordResetToken.class);
        if (consumed == null) throw new IllegalArgumentException("This reset link is invalid or has expired.");
        User user = userRepository.findById(consumed.getUserId())
                .filter(User::isEnabled).orElseThrow(() -> new IllegalArgumentException("This reset link is invalid or has expired."));
        userService.applyNewPassword(user, newPassword);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
