package com.eventflow;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

class SampleEventsSchedulingTest {
    @Test
    void freeDayStaysInsideTheWindowAndRespectsThePerDayCap() throws Exception {
        Class<?> cls = Class.forName("com.eventflow.config.SampleEventsSeeder");
        Method m = cls.getDeclaredMethod("freeDay", LocalDate.class, Map.class, ThreadLocalRandom.class);
        m.setAccessible(true);
        LocalDate today = LocalDate.of(2026, 11, 19);
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (int i = 0; i < 150; i++) {
            LocalDate d = (LocalDate) m.invoke(null, today, perDay, ThreadLocalRandom.current());
            assertFalse(d.isBefore(today.plusDays(3)), "never sooner than 3 days out");
            assertFalse(d.isAfter(today.plusDays(90)), "never beyond the 90-day window");
            perDay.merge(d, 1, Integer::sum);
        }
        assertTrue(perDay.values().stream().allMatch(c -> c <= 2), "at most 2 events per day while free days exist");
        assertTrue(perDay.size() > 40, "dates are spread out, not clustered");
    }
}
