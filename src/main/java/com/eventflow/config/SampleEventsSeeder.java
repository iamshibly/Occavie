package com.eventflow.config;

import com.eventflow.model.Event;
import com.eventflow.model.EventScheduleItem;
import com.eventflow.model.EventTicketType;
import com.eventflow.model.User;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.EventScheduleRepository;
import com.eventflow.repository.EventTicketTypeRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Optional sample content (OCCAVIE_SAMPLE_EVENTS=true) that keeps the site from ever looking empty.
 * <ul>
 *   <li>Empty database: loads the sample events.</li>
 *   <li>Every start and every day at 03:10: keeps at least {@value #MIN_UPCOMING} sample events inside the next
 *       {@value #WINDOW_DAYS} days, with dates shuffled and at most {@value #MAX_PER_DAY} per day.
 *       Expired events nobody booked get a new random date; expired events with bookings stay as history and a fresh
 *       copy is scheduled; if still short, missing sample events are created.</li>
 * </ul>
 * Only events owned by the non-login "Occavie Events" account are ever touched. Real organizers' events are never changed.
 */
@Component
public class SampleEventsSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SampleEventsSeeder.class);
    private static final String ORGANIZER_EMAIL = "events@occavie.local";
    static final int MIN_UPCOMING = 24;
    static final int WINDOW_DAYS = 90;
    static final int MAX_PER_DAY = 2;

    record Row(String title, String category, String venue, int days, int hour, int minute, int capacity, int price) { }

    private static final List<Row> ROWS = List.of(
        new Row("AI & Cloud Innovation Summit", "Technology", "BICC, Dhaka", 5, 9, 30, 350, 800),
        new Row("Morning Yoga by the Lake", "Health", "Dhanmondi Lake", 6, 6, 30, 80, 0),
        new Row("Flutter Mobile Dev Day", "Technology", "Bangladesh Startup Hub", 7, 10, 0, 160, 400),
        new Row("Full Stack Web Bootcamp", "Technology", "SEU Computer Lab", 8, 10, 0, 100, 500),
        new Row("Inter-Department Cricket Cup", "Sports", "SEU Sports Ground", 9, 14, 0, 600, 100),
        new Row("Resume & LinkedIn Clinic", "Career", "SEU Seminar Hall", 10, 11, 0, 140, 0),
        new Row("Cloud Native Meetup", "Technology", "BICC, Dhaka", 11, 17, 0, 220, 0),
        new Row("Campus Music Fiesta", "Music", "Southeast University Auditorium", 12, 18, 0, 500, 300),
        new Row("Tree Plantation Drive", "Community", "Hatirjheel, Dhaka", 12, 8, 0, 200, 0),
        new Row("Intro to Machine Learning", "Education", "SEU CSE Lab 2", 13, 10, 30, 90, 300),
        new Row("Women in Leadership Summit", "Business", "Gulshan Innovation Hub", 14, 9, 30, 300, 900),
        new Row("Entrepreneur Networking Night", "Business", "Gulshan Innovation Hub", 15, 17, 30, 150, 600),
        new Row("Acoustic Evening", "Music", "Banani Creative Studio", 16, 18, 30, 150, 350),
        new Row("Small Business Finance Clinic", "Business", "Motijheel Training Centre", 17, 11, 0, 110, 250),
        new Row("UX Product Design Workshop", "Design", "Banani Creative Studio", 18, 11, 0, 90, 450),
        new Row("Nutrition & Fitness Workshop", "Health", "SEU Community Hall", 19, 10, 0, 120, 150),
        new Row("Career & Internship Expo", "Career", "Southeast University, Dhaka", 21, 10, 0, 650, 0),
        new Row("Poster & Brand Design Jam", "Design", "Banani Creative Studio", 21, 11, 0, 70, 300),
        new Row("5K Fun Run for Clean Air", "Sports", "Hatirjheel Running Track", 23, 6, 45, 700, 200),
        new Row("Robotics & IoT Showcase", "Technology", "Dhaka Innovation Center", 24, 14, 0, 240, 0),
        new Row("Neighbourhood Book Swap", "Community", "Dhanmondi Lake", 25, 16, 0, 120, 0),
        new Row("Health & Wellness Awareness Camp", "Health", "SEU Community Hall", 26, 9, 0, 180, 0),
        new Row("Scholarship & Study Abroad Fair", "Education", "SEU Central Auditorium", 27, 10, 0, 800, 0),
        new Row("Open Source Sprint", "Technology", "SEU Computer Lab", 28, 9, 30, 85, 0),
        new Row("Inter-University Football Tournament", "Sports", "Dhaka University Sports Ground", 29, 15, 0, 800, 150),
        new Row("Battle of the Bands", "Music", "SEU Open Stage", 30, 17, 0, 900, 250),
        new Row("Data Science for Beginners", "Education", "SEU CSE Lab 2", 32, 10, 30, 100, 300),
        new Row("Figma for Beginners", "Design", "SEU Seminar Hall", 33, 14, 0, 100, 0),
        new Row("Startup Pitch Day", "Business", "Bangladesh Startup Hub", 35, 11, 0, 200, 500),
        new Row("Tech Hiring Day", "Career", "International Convention City", 36, 10, 0, 500, 0),
        new Row("Photography & Storytelling Meetup", "Community", "Hatirjheel, Dhaka", 38, 8, 0, 100, 0),
        new Row("Cybersecurity CTF Night", "Technology", "Dhaka Innovation Center", 40, 18, 0, 180, 200),
        new Row("Cybersecurity Conference", "Technology", "International Convention City", 42, 9, 30, 450, 1000),
        new Row("Investor Speed Meetings", "Business", "Gulshan Business Centre", 44, 13, 0, 90, 600),
        new Row("Digital Marketing Masterclass", "Business", "Dhanmondi Training Centre", 45, 11, 0, 120, 350),
        new Row("Mental Wellbeing Circle", "Health", "SEU Community Hall", 47, 15, 0, 60, 0),
        new Row("UI Design Portfolio Review", "Design", "Banani Creative Studio", 48, 14, 0, 80, 400),
        new Row("Table Tennis Open", "Sports", "SEU Indoor Hall", 50, 15, 30, 128, 80),
        new Row("University Job Fair", "Career", "SEU Central Auditorium", 52, 10, 0, 750, 0),
        new Row("Research Paper Writing Workshop", "Education", "SEU Library Hall", 53, 10, 0, 100, 0),
        new Row("Marathon for a Cause", "Sports", "Hatirjheel Running Track", 55, 7, 0, 600, 250),
        new Row("Folk Music Mehfil", "Music", "University Auditorium", 56, 18, 0, 400, 150),
        new Row("Blockchain Technology Forum", "Technology", "BICC, Dhaka", 58, 10, 0, 250, 700),
        new Row("Motion Graphics Workshop", "Design", "Banani Creative Studio", 59, 11, 0, 75, 450),
        new Row("Music & Cultural Festival", "Music", "SEU Open Stage", 61, 17, 0, 900, 200),
        new Row("Internship Showcase", "Career", "SEU Central Auditorium", 62, 11, 0, 450, 0),
        new Row("Women in Technology Panel", "Education", "Southeast University Auditorium", 64, 15, 0, 230, 0),
        new Row("Charity Food Fair", "Community", "Southeast University Ground", 66, 12, 0, 1000, 0),
        new Row("Mobile App Hackathon", "Technology", "SEU Innovation Lab", 68, 9, 0, 140, 0),
        new Row("DevOps Practice Day", "Technology", "BICC, Dhaka", 70, 9, 30, 150, 500),
        new Row("Public Speaking Workshop", "Education", "SEU Seminar Hall", 72, 11, 0, 90, 200),
        new Row("Startup Weekend Dhaka", "Business", "Bangladesh Startup Hub", 74, 9, 0, 250, 700),
        new Row("Creative Entrepreneurship Fest", "Business", "Gulshan Business Centre", 75, 12, 0, 320, 500),
        new Row("Community Volunteer Meetup", "Community", "Dhanmondi Lake", 80, 9, 0, 120, 0));

    /** Real photos placed on the events they suit best; every other event gets its own generated image (/images/event-photos/<slug>.webp). */
    private static final java.util.Map<String, String> PHOTOS = java.util.Map.of(
            "AI & Cloud Innovation Summit", "/images/photos/conference.webp",
            "Resume & LinkedIn Clinic", "/images/photos/business-laptop.webp",
            "UI Design Portfolio Review", "/images/photos/design-desk.webp",
            "University Job Fair", "/images/photos/career-fair.webp",
            "Campus Music Fiesta", "/images/photos/music.webp",
            "Entrepreneur Networking Night", "/images/photos/networking.webp",
            "Full Stack Web Bootcamp", "/images/photos/workshop.webp",
            "Cybersecurity Conference", "/images/photos/tech-lock.webp");

    /** Replaced photos get a new file name so browsers that cached the earlier file fetch the new one. */
    private static final Map<String, String> REVISIONS = Map.of(
            "flutter-mobile-dev-day", "-2",
            "inter-department-cricket-cup", "-2");

    static String imageFor(String title) {
        String photo = PHOTOS.get(title);
        if (photo != null) return photo;
        String slug = title.toLowerCase(java.util.Locale.ROOT).replace("&", "and").replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return "/images/event-photos/" + slug + REVISIONS.getOrDefault(slug, "") + ".webp";
    }


    private final boolean enabled;
    private final EventRepository events;
    private final EventTicketTypeRepository tickets;
    private final EventScheduleRepository schedule;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final RegistrationRepository registrations;

    public SampleEventsSeeder(@Value("${occavie.sample-events.enabled:false}") boolean enabled, EventRepository events,
                              EventTicketTypeRepository tickets, EventScheduleRepository schedule, UserRepository users,
                              PasswordEncoder encoder, RegistrationRepository registrations) {
        this.enabled = enabled; this.events = events; this.tickets = tickets; this.schedule = schedule;
        this.users = users; this.encoder = encoder; this.registrations = registrations;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (events.count() == 0) seedInitial();
        else refreshImages();
        maintain();
    }

    private User organizer() {
        return users.findByEmailIgnoreCase(ORGANIZER_EMAIL).orElseGet(() -> users.save(User.builder()
                .fullName("Occavie Events").email(ORGANIZER_EMAIL).role("ORGANIZER").enabled(true)
                .password(encoder.encode(UUID.randomUUID() + "Aa1!")).build()));   // random password: nobody can log in
    }

    private void seedInitial() {
        User organizer = organizer();
        LocalDate today = LocalDate.now();
        int index = 0;
        for (Row r : ROWS) createEvent(r, organizer, today.plusDays(r.days()), index++ < 6);
        log.info("Loaded {} sample events (OCCAVIE_SAMPLE_EVENTS=true).", ROWS.size());
    }

    /** Creates one sample event with its ticket types and schedule. */
    private Event createEvent(Row r, User organizer, LocalDate date, boolean featured) {
        boolean paid = r.price() > 0;
        Event event = events.save(Event.builder().title(r.title()).category(r.category()).location(r.venue())
                .date(date).time(LocalTime.of(r.hour(), r.minute()))
                .description(r.title() + " brings people together at " + r.venue() + " for talks, hands-on sessions and networking.")
                .capacity(r.capacity()).status("UPCOMING").featured(featured)
                .organizerId(organizer.getId()).organizerName(organizer.getFullName())
                .imageUrl(imageFor(r.title())).pricingType(paid ? "PAID" : "FREE").basePrice(BigDecimal.valueOf(r.price())).build());
        int standard = paid ? (int) Math.floor(r.capacity() * 0.8) : r.capacity();
        List<EventTicketType> ticketRows = new ArrayList<>();
        ticketRows.add(EventTicketType.builder().eventId(event.getId()).name(paid ? "Standard" : "General Admission")
                .description(paid ? "Regular event entry" : "Free admission").price(BigDecimal.valueOf(r.price()))
                .capacity(standard).active(true).sortOrder(1).build());
        if (paid) ticketRows.add(EventTicketType.builder().eventId(event.getId()).name("VIP")
                .description("Priority seating and enhanced access").price(BigDecimal.valueOf(r.price() * 2L))
                .capacity(r.capacity() - standard).active(true).sortOrder(2).build());
        tickets.saveAll(ticketRows);
        int[][] parts = {{0, 25}, {30, 100}, {110, 155}};
        String[] names = {"Registration & Welcome", "Main Session: " + r.title(), "Questions & Networking"};
        List<EventScheduleItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int from = r.hour() * 60 + r.minute() + parts[i][0], to = r.hour() * 60 + r.minute() + parts[i][1];
            items.add(EventScheduleItem.builder().eventId(event.getId()).title(names[i])
                    .startTime(LocalTime.of((from / 60) % 24, from % 60)).endTime(LocalTime.of((to / 60) % 24, to % 60))
                    .location(r.venue()).description("").build());
        }
        schedule.saveAll(items);
        return event;
    }

    /** Sample events loaded earlier carry no (or an older) image; give each its own. */
    private void refreshImages() {
        users.findByEmailIgnoreCase(ORGANIZER_EMAIL).ifPresent(organizer -> {
            int updated = 0;
            for (Event event : events.findByOrganizerIdOrderByDateAsc(organizer.getId())) {
                String img = event.getImageUrl();
                boolean placeholder = img == null || img.isBlank() || "/images/event-visual.png".equals(img);
                boolean oldSamplePath = img != null && img.startsWith("/images/events/");   // earlier generated artwork
                String expected = imageFor(event.getTitle());
                boolean outdated = img != null && img.startsWith("/images/event-photos/") && !img.equals(expected);
                if (!placeholder && !oldSamplePath && !outdated) continue;
                event.setImageUrl(expected);
                events.save(event);
                updated++;
            }
            if (updated > 0) log.info("Gave {} sample events their own image.", updated);
        });
    }

    /** Daily (and at start-up): keep the upcoming list populated with shuffled dates. */
    @Scheduled(cron = "0 10 3 * * *")
    public void maintain() {
        if (!enabled) return;
        User organizer = organizer();
        LocalDate today = LocalDate.now();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Event> mine = events.findByOrganizerIdOrderByDateAsc(organizer.getId());

        Map<LocalDate, Integer> perDay = new HashMap<>();
        Set<String> upcomingTitles = new HashSet<>();
        int upcoming = 0;
        for (Event e : mine) {
            if (e.getDate() != null && !e.getDate().isBefore(today)) {
                perDay.merge(e.getDate(), 1, Integer::sum);
                upcomingTitles.add(e.getTitle());
                upcoming++;
            }
        }

        int moved = 0, copied = 0, created = 0;
        Map<String, Row> byTitle = new HashMap<>();
        for (Row r : ROWS) byTitle.put(r.title(), r);

        for (Event e : mine) {
            if (e.getDate() == null || !e.getDate().isBefore(today)) continue;
            if (registrations.countByEventId(e.getId()) == 0) {
                // nobody booked it: it simply gets a new random date
                LocalDate next = freeDay(today, perDay, random);
                e.setDate(next);
                events.save(e);
                perDay.merge(next, 1, Integer::sum); upcomingTitles.add(e.getTitle()); upcoming++; moved++;
            } else if (!upcomingTitles.contains(e.getTitle()) && byTitle.containsKey(e.getTitle())) {
                // booked: keep it as history and schedule a fresh copy
                LocalDate next = freeDay(today, perDay, random);
                createEvent(byTitle.get(e.getTitle()), organizer, next, false);
                perDay.merge(next, 1, Integer::sum); upcomingTitles.add(e.getTitle()); upcoming++; copied++;
            }
        }

        // top up: create sample events that have no upcoming date yet until the minimum is reached
        List<Row> pool = new ArrayList<>(ROWS);
        java.util.Collections.shuffle(pool, random);
        for (Row r : pool) {
            if (upcoming >= MIN_UPCOMING) break;
            if (upcomingTitles.contains(r.title())) continue;
            LocalDate next = freeDay(today, perDay, random);
            createEvent(r, organizer, next, false);
            perDay.merge(next, 1, Integer::sum); upcomingTitles.add(r.title()); upcoming++; created++;
        }
        if (moved + copied + created > 0) {
            log.info("Sample events: {} re-dated, {} rescheduled copies, {} created; {} upcoming.", moved, copied, created, upcoming);
        }
    }

    /** A random day in [today+3, today+WINDOW_DAYS] with fewer than MAX_PER_DAY events (falls back to any day in the window). */
    static LocalDate freeDay(LocalDate today, Map<LocalDate, Integer> perDay, ThreadLocalRandom random) {
        for (int attempt = 0; attempt < 60; attempt++) {
            LocalDate d = today.plusDays(3 + random.nextInt(WINDOW_DAYS - 2));
            if (perDay.getOrDefault(d, 0) < MAX_PER_DAY) return d;
        }
        return today.plusDays(3 + random.nextInt(WINDOW_DAYS - 2));
    }
}
