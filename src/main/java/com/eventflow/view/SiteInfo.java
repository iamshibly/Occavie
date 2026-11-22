package com.eventflow.view;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Public site details (developer credit and contact channels) read from configuration, never hard-coded in templates. */
@Component("site")
public class SiteInfo {
    private final String developerName;
    private final String developerUrl;
    private final String contactEmail;
    private final String contactWhatsapp;
    private final String contactAddress;
    private final String facebookUrl;
    private final String linkedinUrl;

    public SiteInfo(@Value("${occavie.developer.name:Md. Zubayer Ahmad Shibly}") String developerName,
                    @Value("${occavie.developer.url:https://github.com/iamshibly}") String developerUrl,
                    @Value("${occavie.contact.email:}") String contactEmail,
                    @Value("${occavie.contact.whatsapp:}") String contactWhatsapp,
                    @Value("${occavie.contact.address:Dhaka, Bangladesh}") String contactAddress,
                    @Value("${occavie.developer.facebook:}") String facebookUrl,
                    @Value("${occavie.developer.linkedin:}") String linkedinUrl) {
        this.developerName = developerName.trim();
        this.developerUrl = developerUrl.trim();
        this.contactEmail = contactEmail.trim();
        this.contactWhatsapp = contactWhatsapp.replaceAll("[^0-9]", "");
        this.contactAddress = contactAddress.trim();
        this.facebookUrl = safeUrl(facebookUrl);
        this.linkedinUrl = safeUrl(linkedinUrl);
    }

    public String getDeveloperName() { return developerName; }
    public String getDeveloperUrl() { return developerUrl; }
    public String getContactEmail() { return contactEmail; }
    public String getContactWhatsapp() { return contactWhatsapp; }
    public String getContactAddress() { return contactAddress; }
    public String getFacebookUrl() { return facebookUrl; }
    public String getLinkedinUrl() { return linkedinUrl; }

    /** Only https links are ever rendered. */
    private static String safeUrl(String url) {
        String u = url == null ? "" : url.trim();
        return u.startsWith("https://") ? u : "";
    }

    public String getDeveloperInitials() {
        String[] parts = developerName.replace(".", " ").trim().split("\\s+");
        String first = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "O";
        String letters = parts.length > 1 ? "" + first.charAt(0) + parts[parts.length - 1].charAt(0) : first.substring(0, Math.min(2, first.length()));
        return letters.toUpperCase();
    }
}
