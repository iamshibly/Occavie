package com.eventflow.service;

import com.eventflow.model.EmailMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resend transactional email over HTTPS (works on hosts that block SMTP ports, such as Render's free tier).
 * The API key comes from the environment. The outbox dedupe key is sent as the Idempotency-Key header, so a
 * retry after a timeout cannot deliver the same email twice.
 */
public class ResendEmailProvider implements EmailProvider {
    private static final URI ENDPOINT = URI.create("https://api.resend.com/emails");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String from;

    public ResendEmailProvider(String apiKey, String from) {
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public boolean enabled() { return true; }

    @Override
    public void send(EmailMessage message) throws EmailException {
        String json = "{\"from\":" + quote(from) + ",\"to\":[" + quote(message.getToEmail()) + "],\"subject\":" + quote(message.getSubject())
                + ",\"text\":" + quote(message.getTextBody())
                + (message.getHtmlBody() == null ? "" : ",\"html\":" + quote(message.getHtmlBody())) + "}";
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", message.getDedupeKey())
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return;
            // 4xx (except 429) means the message itself is rejected: retrying will not help.
            boolean retry = status == 429 || status >= 500;
            throw new EmailException("Resend responded " + status, retry);
        } catch (IOException ex) {
            throw new EmailException("Network error: " + ex.getClass().getSimpleName(), true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmailException("Interrupted", true);
        }
    }

    public static String quote(String value) {
        if (value == null) return "\"\"";
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
