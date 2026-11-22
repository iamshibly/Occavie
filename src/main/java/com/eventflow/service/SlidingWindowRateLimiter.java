package com.eventflow.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory sliding-window limiter. It protects a single application instance (Occavie runs as one web
 * service); with several instances each would keep its own counters, so a shared store would be needed then.
 */
public class SlidingWindowRateLimiter {
    private static final int CLEANUP_THRESHOLD = 20_000;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Records an attempt for {@code key}.
     * @return 0 when allowed, otherwise the number of seconds after which the caller may retry
     */
    public long tryAcquire(String key, int limit, long windowMillis, long nowMillis) {
        if (hits.size() > CLEANUP_THRESHOLD) cleanup(nowMillis - windowMillis);
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            long cutoff = nowMillis - windowMillis;
            while (!deque.isEmpty() && deque.peekFirst() <= cutoff) deque.pollFirst();
            if (deque.size() >= limit) {
                long retryMillis = deque.peekFirst() + windowMillis - nowMillis;
                return Math.max(1, (retryMillis + 999) / 1000);
            }
            deque.addLast(nowMillis);
            return 0;
        }
    }

    private void cleanup(long cutoff) {
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long last = e.getValue().peekLast();
                return last == null || last <= cutoff;
            }
        });
    }
}
