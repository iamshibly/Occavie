package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.model.User;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Public organizer pages. Only opt-in fields (bio, website, contact email) are exposed; never the account email. */
@Service
@RequiredArgsConstructor
public class OrganizerProfileService {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public record PublicProfile(User organizer, List<Event> upcoming, List<Event> past) { }

    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final UserService userService;

    /** Empty when the id is not an active organizer. */
    public Optional<PublicProfile> publicProfile(String organizerId) {
        return userRepository.findById(organizerId == null ? "" : organizerId)
                .filter(u -> "ORGANIZER".equals(u.getRole()) && u.isEnabled())
                .map(u -> {
                    List<Event> events = eventRepository.findByOrganizerIdOrderByDateAsc(u.getId()).stream()
                            .filter(EventRules::isPublished).toList();
                    List<Event> upcoming = events.stream().filter(EventRules::bookable)
                            .filter(e -> !"COMPLETED".equals(EventRules.status(e))).toList();
                    List<Event> past = events.stream().filter(e -> "COMPLETED".equals(EventRules.status(e)))
                            .sorted(Comparator.comparing(Event::getDate, Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(12).toList();
                    return new PublicProfile(u, upcoming, past);
                });
    }

    public boolean hasPublicPage(String organizerId) {
        return organizerId != null && userRepository.findById(organizerId).filter(u -> "ORGANIZER".equals(u.getRole()) && u.isEnabled()).isPresent();
    }

    public User updatePublicProfile(User organizer, String bio, String website, String contactEmail) {
        if (organizer == null || !"ORGANIZER".equals(organizer.getRole())) {
            throw new IllegalArgumentException("Only organizers have a public profile.");
        }
        String cleanBio = bio == null ? "" : bio.trim();
        if (cleanBio.length() > 300) throw new IllegalArgumentException("Keep your description under 300 characters.");
        String cleanSite = website == null ? "" : website.trim();
        if (!cleanSite.isEmpty()) {
            try {
                URI uri = URI.create(cleanSite);
                if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Enter a valid website starting with https://");
            }
            if (cleanSite.length() > 200) throw new IllegalArgumentException("Website address is too long.");
        }
        String cleanEmail = contactEmail == null ? "" : contactEmail.trim();
        if (!cleanEmail.isEmpty() && (!EMAIL.matcher(cleanEmail).matches() || cleanEmail.length() > 120)) {
            throw new IllegalArgumentException("Enter a valid public contact email or leave it blank.");
        }
        organizer.setBio(cleanBio.isEmpty() ? null : cleanBio);
        organizer.setWebsite(cleanSite.isEmpty() ? null : cleanSite);
        organizer.setPublicContactEmail(cleanEmail.isEmpty() ? null : cleanEmail);
        return userRepository.save(organizer);
    }
}
