package com.eventflow.service;

import com.eventflow.model.EmailMessage;

/** Sends one email. Implementations throw {@link EmailException} on failure so the outbox can retry. */
public interface EmailProvider {
    /** False for the no-op provider: messages are recorded as SKIPPED instead of queued. */
    boolean enabled();

    void send(EmailMessage message) throws EmailException;

    class EmailException extends Exception {
        private final boolean retryable;
        public EmailException(String message, boolean retryable) { super(message); this.retryable = retryable; }
        public boolean retryable() { return retryable; }
    }
}
