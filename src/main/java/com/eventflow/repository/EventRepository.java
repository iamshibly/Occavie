package com.eventflow.repository;

import com.eventflow.model.Event;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;

public interface EventRepository extends MongoRepository<Event, String> {
    List<Event> findTop3ByFeaturedTrueOrderByDateAsc();
    List<Event> findAllByOrderByDateAsc();
    List<Event> findByOrganizerIdOrderByDateAsc(String organizerId);
    long countByOrganizerId(String organizerId);
    /** Simple category/date filters used for suggestions (status UPCOMING = published and not cancelled). */
    List<Event> findTop12ByCategoryAndStatusAndDateGreaterThanEqualOrderByDateAsc(String category, String status, LocalDate from);
    List<Event> findByStatusOrderBySubmittedAtAsc(String status);
    long countByStatus(String status);
    List<Event> findByStatusAndDateBetween(String status, LocalDate from, LocalDate to);
    List<Event> findTop12ByStatusAndDateGreaterThanEqualOrderByDateAsc(String status, LocalDate from);
}
