package com.eventflow.controller;

import com.eventflow.model.Event;
import com.eventflow.service.EventRules;
import com.eventflow.service.EventService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** robots.txt, sitemap.xml and health endpoints (all public, none expose internals). */
@RestController
@RequiredArgsConstructor
public class SeoController {
    private static final int SITEMAP_LIMIT = 2000;

    private final EventService eventService;
    private final com.eventflow.repository.UserRepository userRepository;
    private final MongoTemplate mongoTemplate;

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots(HttpServletRequest request) {
        String base = base(request);
        String body = "User-agent: *\nAllow: /\n"
                + "Disallow: /dashboard\nDisallow: /admin/\nDisallow: /organizer/\nDisallow: /participant/\nDisallow: /profile\nDisallow: /compare\n"
                + "Disallow: /notifications\nDisallow: /login\nDisallow: /register\nDisallow: /reset-password\nDisallow: /internal/\n\n"
                + "Sitemap: " + base + "/sitemap.xml\n";
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(body);
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap(HttpServletRequest request) {
        String base = base(request);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        add(xml, base + "/", null, "daily");
        add(xml, base + "/events", null, "daily");
        add(xml, base + "/about", null, "monthly");
        add(xml, base + "/contact", null, "yearly");
        List<Event> events = eventService.findAllSorted().stream().filter(EventRules::isPublished).limit(SITEMAP_LIMIT).toList();
        java.util.Set<String> organizerIds = new java.util.LinkedHashSet<>();
        for (Event event : events) {
            add(xml, base + "/events/" + event.getId(), event.getDate() == null ? null : event.getDate().toString(), "weekly");
            if (event.getOrganizerId() != null) organizerIds.add(event.getOrganizerId());
        }
        for (String id : organizerIds) {
            userRepository.findById(id).filter(u -> "ORGANIZER".equals(u.getRole()) && u.isEnabled())
                    .ifPresent(u -> add(xml, base + "/organizers/" + id, null, "weekly"));
        }
        xml.append("</urlset>\n");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(xml.toString());
    }

    /** Liveness: the process is up. No database call, so a database blip never restarts the app. */
    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Readiness: the database answers a ping. */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            mongoTemplate.getDb().runCommand(new Document("ping", 1));
            return ResponseEntity.ok(Map.of("status", "UP", "database", "UP"));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN", "database", "DOWN"));
        }
    }

    private static void add(StringBuilder xml, String loc, String lastmod, String changefreq) {
        xml.append("  <url><loc>").append(escape(loc)).append("</loc>");
        if (lastmod != null) xml.append("<lastmod>").append(lastmod).append("</lastmod>");
        xml.append("<changefreq>").append(changefreq).append("</changefreq></url>\n");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String base(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request).toUriString();
    }
}
