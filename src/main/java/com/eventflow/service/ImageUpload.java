package com.eventflow.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Validates uploaded images beyond the client-supplied Content-Type: the file's leading bytes (its signature)
 * must be JPEG, PNG or WebP and must agree with the declared type, and the size must be within the 2 MB limit.
 * A renamed script or HTML file therefore cannot be stored as an "image".
 */
public final class ImageUpload {
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private ImageUpload() {}

    /** @throws IllegalArgumentException with a user-facing message when the file is not an acceptable image */
    public static void verify(MultipartFile file, String label) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose an image to upload.");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException(label + " must be 2 MB or smaller.");
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(12);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read the uploaded image. Try again.");
        }
        String detected = detect(head);
        if (detected == null || !detected.equals(declared)) {
            throw new IllegalArgumentException(label + " must be a real JPG, PNG or WEBP image.");
        }
    }

    /** MIME type from the file signature, or null when it is not a supported image. */
    public static String detect(byte[] head) {
        if (head == null) return null;
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) return "image/png";
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return "image/webp";
        return null;
    }
}
