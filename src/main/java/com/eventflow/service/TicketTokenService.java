package com.eventflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues and verifies QR ticket tokens.
 *
 * <p>A token is {@code OVT1.<code>.<mac>}. {@code code} is 128 random bits stored on the registration
 * (it is NOT the database id and is not derived from it). {@code mac} is an HMAC-SHA256 of the code under a
 * server-side secret, so a token cannot be forged or tweaked without the secret, and a leaked database alone
 * does not yield valid QR codes. Verification always happens on the server.
 */
@Service
public class TicketTokenService {
    static final String PREFIX = "OVT1";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    public TicketTokenService(@Value("${occavie.ticket.secret:${occavie.security.remember-key:occavie-local-remember-key}}") String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** New random registration code (128 bits, URL-safe). */
    public String newCode() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    /** The full token to print in the QR code for a registration code. */
    public String tokenFor(String code) {
        return PREFIX + "." + code + "." + mac(code);
    }

    /** Returns the registration code if (and only if) the token is well-formed and correctly signed. */
    public Optional<String> codeFrom(String token) {
        if (token == null) return Optional.empty();
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0]) || parts[1].length() != 22 || parts[2].length() != 22) {
            return Optional.empty();
        }
        byte[] expected = mac(parts[1]).getBytes(StandardCharsets.UTF_8);
        byte[] given = parts[2].getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given) ? Optional.of(parts[1]) : Optional.empty();
    }

    private String mac(String code) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = hmac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return B64.encodeToString(java.util.Arrays.copyOf(full, 16));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }
}
