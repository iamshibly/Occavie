package com.eventflow.controller;

import com.eventflow.service.ScheduledJobs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Lets an external scheduler trigger the background jobs (reminders, email delivery, expired waitlist offers).
 * Disabled (404) unless OCCAVIE_JOBS_TOKEN is set; the token is compared in constant time.
 */
@RestController
@RequiredArgsConstructor
public class InternalJobsController {
    private final ScheduledJobs jobs;

    @Value("${occavie.jobs.token:}")
    private String token;

    @PostMapping("/internal/jobs/run")
    public ResponseEntity<Map<String, Object>> run(@RequestHeader(value = "X-Jobs-Token", required = false) String provided) {
        if (token == null || token.isBlank()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        if (provided == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(jobs.run());
    }
}
