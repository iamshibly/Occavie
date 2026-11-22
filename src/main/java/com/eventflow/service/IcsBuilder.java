package com.eventflow.service;

import com.eventflow.model.Event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an RFC 5545 calendar file for one event. Start and end are converted from the event's configured
 * time zone to UTC, so every calendar app shows the same moment regardless of the viewer's zone.
 */
public final class IcsBuilder {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private IcsBuilder() {}

    public static String build(Event event, LocalDateTime endLocal, ZoneId zone, String eventUrl, Instant now) {
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:-//Occavie//Events//EN");
        lines.add("CALSCALE:GREGORIAN");
        lines.add("METHOD:PUBLISH");
        lines.add("X-WR-CALNAME:Occavie");
        lines.add("X-WR-TIMEZONE:" + zone.getId());
        lines.add("BEGIN:VEVENT");
        lines.add("UID:" + event.getId() + "@occavie");
        lines.add("DTSTAMP:" + UTC.format(now.atOffset(ZoneOffset.UTC)));
        if (event.getTime() == null) {
            lines.add("DTSTART;VALUE=DATE:" + DATE.format(event.getDate()));
            lines.add("DTEND;VALUE=DATE:" + DATE.format(event.getDate().plusDays(1)));
        } else {
            LocalDateTime start = LocalDateTime.of(event.getDate(), event.getTime());
            LocalDateTime end = endLocal != null && endLocal.isAfter(start) ? endLocal : start.plusHours(2);
            lines.add("DTSTART:" + UTC.format(start.atZone(zone).withZoneSameInstant(ZoneOffset.UTC)));
            lines.add("DTEND:" + UTC.format(end.atZone(zone).withZoneSameInstant(ZoneOffset.UTC)));
        }
        lines.add("SUMMARY:" + escape(event.getTitle()));
        StringBuilder description = new StringBuilder();
        if (event.getDescription() != null) description.append(event.getDescription().trim()).append("\n\n");
        if (event.getOrganizerName() != null) description.append("Organizer: ").append(event.getOrganizerName()).append('\n');
        description.append("Details: ").append(eventUrl);
        lines.add("DESCRIPTION:" + escape(description.toString()));
        String where = event.getAddress() != null && !event.getAddress().isBlank()
                ? event.getLocation() + ", " + event.getAddress().trim() : event.getLocation();
        if (where != null) lines.add("LOCATION:" + escape(where));
        lines.add("URL:" + eventUrl);
        lines.add("STATUS:" + (EventRules.isCancelled(event) ? "CANCELLED" : "CONFIRMED"));
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(fold(line)).append("\r\n");
        return out.toString();
    }

    /** Escapes backslash, semicolon, comma and newlines in TEXT values. */
    static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "");
    }

    /** Lines are folded at 75 octets (never splitting a UTF-8 character) with a leading space on continuations. */
    static String fold(String line) {
        if (line.getBytes(StandardCharsets.UTF_8).length <= 75) return line;
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        boolean first = true;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            int limit = first ? 75 : 74;   // continuation lines start with one space
            if (bytes + size > limit) { out.append("\r\n "); bytes = 0; first = false; }
            out.appendCodePoint(cp);
            bytes += size;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
