package com.eventflow.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Clears every cached event list; put on any method that creates, changes or removes events. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Caching(evict = {
        @CacheEvict(value = "eventsAll", allEntries = true, beforeInvocation = true),
        @CacheEvict(value = "featuredEvents", allEntries = true, beforeInvocation = true),
        @CacheEvict(value = "eventsByOrganizer", allEntries = true, beforeInvocation = true),
        @CacheEvict(value = "eventCount", allEntries = true, beforeInvocation = true)
})
public @interface EvictEventCaches {
}
