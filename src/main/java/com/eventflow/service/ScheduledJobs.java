package com.eventflow.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background work: due reminders, expired waitlist offers and email delivery.
 *
 * <p>Hosting model: every piece of state (outbox rows, dedupe keys, offer expiry times) is in MongoDB, so a restart,
 * redeploy or sleeping instance loses nothing and a late run simply catches up. The in-process poller covers an awake
 * instance; hosts that put the app to sleep can call {@code POST /internal/jobs/run} from an external scheduler
 * (Render Cron Job, cron-job.org, GitHub Actions) which also wakes the app.
 */
@Component
@RequiredArgsConstructor
public class ScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final ReminderService reminderService;
    private final WaitlistService waitlistService;
    private final EmailOutboxService emailOutbox;
    private final AtomicBoolean running = new AtomicBoolean();

    @Value("${occavie.reminder.hours-before:24}")
    private int reminderHours = 24;

    @Scheduled(fixedDelayString = "${occavie.jobs.poll-ms:60000}", initialDelayString = "${occavie.jobs.poll-ms:60000}")
    public void poll() {
        run();
    }

    /** Runs every job once. Overlapping runs are skipped; each step is isolated so one failure cannot block the rest. */
    public Map<String, Object> run() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!running.compareAndSet(false, true)) {
            result.put("skipped", "another run is in progress");
            return result;
        }
        try {
            result.put("remindersCreated", step("reminders", () -> reminderService.enqueueDueReminders(LocalDateTime.now(), reminderHours)));
            result.put("waitlistOffersReleased", step("waitlist", () -> { waitlistService.expireOffers(); return 0; }));
            result.put("emailsSent", step("email", () -> emailOutbox.dispatchDue(50)));
        } finally {
            running.set(false);
        }
        return result;
    }

    private int step(String name, java.util.function.IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (RuntimeException ex) {
            log.warn("Scheduled step '{}' failed: {}", name, ex.toString());
            return -1;
        }
    }
}
