package com.eventflow.config;

import com.eventflow.model.EmailMessage;
import com.eventflow.service.EmailProvider;
import com.eventflow.service.ResendEmailProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the email provider from environment variables. Nothing is hardcoded:
 * {@code OCCAVIE_EMAIL_PROVIDER=resend}, {@code RESEND_API_KEY=...}, {@code OCCAVIE_EMAIL_FROM="Occavie <events@yourdomain>"}.
 * Without them emails are recorded as SKIPPED and in-app notifications keep working.
 */
@Configuration
public class EmailConfig {
    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    EmailProvider emailProvider(@Value("${occavie.email.provider:none}") String provider,
                                @Value("${occavie.email.api-key:}") String apiKey,
                                @Value("${occavie.email.from:}") String from) {
        if ("resend".equalsIgnoreCase(provider) && !apiKey.isBlank() && !from.isBlank()) {
            log.info("Email: Resend enabled");
            return new ResendEmailProvider(apiKey, from);
        }
        if ("resend".equalsIgnoreCase(provider)) {
            log.warn("Email: OCCAVIE_EMAIL_PROVIDER=resend but RESEND_API_KEY or OCCAVIE_EMAIL_FROM is missing; emails are disabled");
        }
        return new EmailProvider() {
            @Override public boolean enabled() { return false; }
            @Override public void send(EmailMessage message) { /* never called */ }
        };
    }
}
