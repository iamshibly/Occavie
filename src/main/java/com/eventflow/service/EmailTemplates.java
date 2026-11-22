package com.eventflow.service;

import java.util.List;

/** Plain, accessible transactional email layouts. All user-supplied text is HTML-escaped. */
public final class EmailTemplates {
    private EmailTemplates() {}

    public record Rendered(String subject, String text, String html) { }

    public static Rendered render(NotificationType type, String recipientName, String eventTitle, String message,
                                  String absoluteLink, List<String> details) {
        String subject = (eventTitle == null || eventTitle.isBlank()) ? type.label() : type.label() + ": " + eventTitle;
        if (subject.length() > 150) subject = subject.substring(0, 147) + "...";
        String greeting = "Hi " + (recipientName == null || recipientName.isBlank() ? "there" : firstName(recipientName)) + ",";

        StringBuilder text = new StringBuilder(greeting).append("\n\n").append(message).append("\n");
        if (details != null && !details.isEmpty()) {
            text.append('\n');
            details.forEach(d -> text.append("- ").append(d).append('\n'));
        }
        if (absoluteLink != null) text.append("\nOpen in Occavie: ").append(absoluteLink).append('\n');
        text.append("\nMake every moment count.\nOccavie\n");

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><body style=\"margin:0;background:#F7F9FE;font-family:Arial,Helvetica,sans-serif;color:#0B1530\">")
            .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" style=\"padding:24px\">")
            .append("<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;border:1px solid #E4E6E1;border-radius:14px\">")
            .append("<tr><td style=\"background:#0B1530;color:#ffffff;padding:18px 24px;border-radius:14px 14px 0 0;font-size:20px;font-weight:bold\">Occavie</td></tr>")
            .append("<tr><td style=\"padding:24px\"><p style=\"margin:0 0 12px\">").append(escape(greeting)).append("</p>")
            .append("<p style=\"margin:0 0 16px;line-height:1.5\">").append(escape(message)).append("</p>");
        if (details != null && !details.isEmpty()) {
            html.append("<ul style=\"margin:0 0 16px;padding-left:18px;color:#3B4856\">");
            details.forEach(d -> html.append("<li>").append(escape(d)).append("</li>"));
            html.append("</ul>");
        }
        if (absoluteLink != null) {
            html.append("<p style=\"margin:20px 0\"><a href=\"").append(escape(absoluteLink))
                .append("\" style=\"background:#875900;color:#ffffff;text-decoration:none;padding:12px 22px;border-radius:999px;font-weight:bold;display:inline-block\">View in Occavie</a></p>");
        }
        html.append("<p style=\"margin:24px 0 0;color:#5B6773;font-size:13px\">Make every moment count.</p></td></tr></table></td></tr></table></body></html>");
        return new Rendered(subject, text.toString(), html.toString());
    }

    static String firstName(String name) {
        String trimmed = name.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
