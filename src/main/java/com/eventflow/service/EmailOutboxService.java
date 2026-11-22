package com.eventflow.service;

import com.eventflow.model.EmailMessage;
import com.eventflow.repository.EmailMessageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * MongoDB-backed email outbox.
 *
 * <ul>
 *   <li>{@link #enqueue} stores the message first. The unique {@code dedupeKey} makes repeated triggers harmless.</li>
 *   <li>{@link #dispatchDue} claims messages atomically with a time-limited lease, so two instances (or a crashed
 *       worker) can never send the same message twice, and an abandoned lease is picked up again later.</li>
 *   <li>Failures are retried with exponential backoff up to {@link #MAX_ATTEMPTS}; permanent errors stop at once.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EmailOutboxService {
    static final int MAX_ATTEMPTS = 5;
    private static final long LEASE_MINUTES = 5;
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxService.class);

    private final EmailMessageRepository repository;
    private final MongoTemplate mongoTemplate;
    private final EmailProvider provider;

    /** @return true when the message was newly queued, false when this dedupe key already exists. */
    public boolean enqueue(String type, String dedupeKey, String toEmail, String toName, String subject, String text, String html) {
        if (dedupeKey == null || dedupeKey.isBlank() || toEmail == null || toEmail.isBlank()) return false;
        if (repository.findByDedupeKey(dedupeKey).isPresent()) return false;
        try {
            repository.save(EmailMessage.builder().type(type).dedupeKey(dedupeKey).toEmail(toEmail).toName(toName)
                    .subject(subject).textBody(text).htmlBody(html)
                    .status(provider.enabled() ? "PENDING" : "SKIPPED")
                    .lastError(provider.enabled() ? null : "Email provider not configured").build());
            return true;
        } catch (DuplicateKeyException ex) {
            return false;   // another request queued it first
        }
    }

    /** Sends due messages; returns how many were sent. Safe to call from several workers at once. */
    public int dispatchDue(int batchSize) {
        if (!provider.enabled()) return 0;
        int sent = 0;
        for (int i = 0; i < batchSize; i++) {
            Optional<EmailMessage> claimed = claimNext(LocalDateTime.now());
            if (claimed.isEmpty()) break;
            EmailMessage message = claimed.get();
            try {
                provider.send(message);
                message.setStatus("SENT");
                message.setSentAt(LocalDateTime.now());
                message.setLastError(null);
                sent++;
            } catch (EmailProvider.EmailException ex) {
                fail(message, ex.getMessage(), ex.retryable());
            } catch (RuntimeException ex) {
                fail(message, ex.getClass().getSimpleName(), true);
            }
            message.setLeaseUntil(null);
            repository.save(message);
        }
        return sent;
    }

    private void fail(EmailMessage message, String error, boolean retryable) {
        message.setLastError(error);
        if (!retryable || message.getAttempts() >= MAX_ATTEMPTS) {
            message.setStatus("FAILED");
            log.warn("Email {} failed permanently after {} attempt(s): {}", message.getType(), message.getAttempts(), error);
        } else {
            message.setStatus("PENDING");
            message.setNextAttemptAt(LocalDateTime.now().plusMinutes(1L << Math.min(message.getAttempts(), 6)));
        }
    }

    /** Atomically leases the next due message (PENDING and due, or SENDING with an expired lease). */
    Optional<EmailMessage> claimNext(LocalDateTime now) {
        Criteria due = new Criteria().orOperator(
                Criteria.where("status").is("PENDING").and("nextAttemptAt").lte(now),
                Criteria.where("status").is("SENDING").and("leaseUntil").lt(now));
        EmailMessage claimed = mongoTemplate.findAndModify(Query.query(due),
                new Update().set("status", "SENDING").set("leaseUntil", now.plusMinutes(LEASE_MINUTES)).inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true), EmailMessage.class);
        return Optional.ofNullable(claimed);
    }
}
