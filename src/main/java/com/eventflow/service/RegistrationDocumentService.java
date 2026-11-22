package com.eventflow.service;

import com.eventflow.model.Event;
import com.eventflow.model.Registration;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Generates repeatable, branded PDFs directly from saved registrations; no payment integration. */
@Service
public class RegistrationDocumentService {

    private final TicketTokenService tokens;

    /** Without a token service (unit tests) tickets are drawn without a QR code. */
    public RegistrationDocumentService() {
        this(null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RegistrationDocumentService(TicketTokenService tokens) {
        this.tokens = tokens;
    }

    private static final Color NAVY = new Color(14, 27, 42);
    private static final Color GOLD = new Color(11, 127, 126);
    private static final Color MUTED = new Color(91, 103, 115);
    private static final Color LINE = new Color(228, 230, 225);
    private static final Color GREEN = new Color(11, 122, 85);
    private static final Color BACKGROUND = new Color(250, 250, 247);
    private static final DateTimeFormatter EVENT_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter PAYMENT_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH);

    public byte[] ticket(Registration registration, Event event) throws IOException {
        return pdfWithImage(drawTicket(registration, event), 960, 521);
    }

    public byte[] receipt(Registration registration, Event event) throws IOException {
        return pdfWithImage(drawReceipt(registration, event), 595, 842);
    }

    private BufferedImage drawTicket(Registration registration, Event event) throws IOException {
        final int w = 1400, h = 760;
        BufferedImage image = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image);
        g.scale(2, 2);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);

        // Paper shadow, round corners and a two-part ticket stub, matching the approved ticket.
        for (int i = 14; i >= 1; i--) {
            g.setColor(new Color(14, 27, 42, 2));
            g.fill(new RoundRectangle2D.Double(25 - i / 3.0, 30 + i / 2.0,
                    1350 + 2 * (i / 3.0), 698, 36, 36));
        }
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(25, 24, 1350, 710, 34, 34));
        g.setColor(LINE);
        g.setStroke(new BasicStroke(1.25f));
        g.draw(new RoundRectangle2D.Double(25, 24, 1350, 710, 34, 34));
        Shape previousClip = g.getClip();
        g.clipRect(26, 25, 994, 708);
        g.setColor(new Color(244, 250, 249));
        g.fillOval(885, 323, 315, 290);
        g.setColor(new Color(255, 255, 255));
        g.fillOval(930, 297, 267, 293);
        g.setColor(new Color(241, 249, 248));
        g.fillOval(783, 456, 225, 250);
        g.setClip(previousClip);

        int splitX = 1024;
        g.setColor(new Color(203, 209, 204));
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                0f, new float[]{9f, 11f}, 0f));
        g.drawLine(splitX, 46, splitX, 713);
        g.setStroke(new BasicStroke(1.1f));
        g.setColor(BACKGROUND);
        g.fillOval(splitX - 24, 0, 48, 48);
        g.fillOval(splitX - 24, 713, 48, 48);
        g.setColor(LINE);
        g.drawArc(splitX - 24, 0, 48, 48, 180, 180);
        g.drawArc(splitX - 24, 713, 48, 48, 0, 180);

        logo(g, 80, 79, 1.0);
        tracked(g, "EVENT TICKET", 745, 121, new Font("SansSerif", Font.BOLD, 17), MUTED, 4);
        g.setColor(GOLD);
        g.fillRoundRect(969, 106, 28, 3, 3, 3);

        String title = clean(event.getTitle(), "Event");
        List<String> titleLines = wrappedTitle(g, title, 900, 57, 2);
        int baseline = titleLines.size() == 1 ? 243 : 207;
        for (String line : titleLines) {
            drawFit(g, line, 90, baseline, 910, new Font("Serif", Font.BOLD, 57), 38, NAVY);
            baseline += 58;
        }
        String subtitle = clean(event.getDescription(), "Your event registration is confirmed.")
                .replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        drawFit(g, subtitle, 92, 312, 880, new Font("SansSerif", Font.PLAIN, 22), 15, MUTED);

        tracked(g, "PARTICIPANT", 92, 385, new Font("SansSerif", Font.BOLD, 15), MUTED, 3);
        drawFit(g, clean(registration.getParticipantName(), "Participant"), 92, 436,
                890, new Font("Serif", Font.BOLD, 44), 25, NAVY);

        calendarIcon(g, 100, 506);
        clockIcon(g, 397, 507);
        pinIcon(g, 683, 505);
        g.setColor(LINE);
        g.drawLine(350, 504, 350, 588);
        g.drawLine(644, 504, 644, 588);
        tracked(g, "DATE", 94, 552, new Font("SansSerif", Font.BOLD, 14), MUTED, 2);
        tracked(g, "TIME", 390, 552, new Font("SansSerif", Font.BOLD, 14), MUTED, 2);
        tracked(g, "VENUE", 680, 552, new Font("SansSerif", Font.BOLD, 14), MUTED, 2);
        drawFit(g, event.getDate() == null ? "TBA" : event.getDate().format(EVENT_DATE),
                94, 582, 241, new Font("Serif", Font.BOLD, 24), 17, NAVY);
        drawFit(g, event.getTime() == null ? "TBA" : event.getTime().format(EVENT_TIME),
                390, 582, 230, new Font("Serif", Font.BOLD, 24), 17, NAVY);
        drawFit(g, clean(event.getLocation(), "TBA"), 680, 582, 308,
                new Font("Serif", Font.BOLD, 23), 16, NAVY);

        g.setColor(LINE);
        g.drawLine(92, 622, 986, 622);
        g.drawLine(392, 648, 392, 710);
        ticketIcon(g, 98, 670);
        tracked(g, "TICKET TYPE", 175, 668, new Font("SansSerif", Font.BOLD, 13), MUTED, 2);
        drawFit(g, clean(registration.getTicketTypeName(), "General Admission"),
                175, 701, 205, new Font("Serif", Font.BOLD, 25), 16, NAVY);
        documentIcon(g, 436, 668);
        tracked(g, "REFERENCE", 499, 668, new Font("SansSerif", Font.BOLD, 13), MUTED, 2);
        drawFit(g, reference(registration), 499, 700, 496,
                new Font("SansSerif", Font.PLAIN, 20), 13, NAVY);

        // Right stub: decorative admission details within the approved perforated layout.
        g.setColor(new Color(227, 245, 236));
        g.fillRoundRect(1060, 85, 275, 75, 40, 40);
        g.setColor(GREEN);
        g.fillOval(1084, 103, 38, 38);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(1095, 123, 1102, 130);
        g.drawLine(1102, 130, 1115, 113);
        drawFit(g, bookingStatus(registration), 1145, 136, 180,
                new Font("Serif", Font.BOLD, 30), 20, NAVY);
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(1098, 228, 197, 197, 22, 22));
        g.setColor(LINE);
        g.draw(new RoundRectangle2D.Double(1098, 228, 197, 197, 22, 22));
        if (!drawQr(g, registration, 1110, 240, 173)) {
            g.setColor(new Color(232, 247, 245));
            g.fill(new RoundRectangle2D.Double(1108, 241, 177, 174, 26, 26));
            bigTicketIcon(g, 1148, 284);
        }
        tracked(g, "SCAN AT ENTRY", 1101, 469, new Font("SansSerif", Font.BOLD, 18), GOLD, 3);
        g.setColor(LINE);
        g.drawLine(1063, 542, 1340, 542);
        tracked(g, "REFERENCE", 1077, 579, new Font("SansSerif", Font.BOLD, 14), MUTED, 2);
        drawFit(g, reference(registration), 1077, 616, 250,
                new Font("SansSerif", Font.BOLD, 16), 11, NAVY);
        drawFit(g, "Present at event entry", 1077, 674, 254,
                new Font("SansSerif", Font.PLAIN, 17), 13, MUTED);
        if (isDemoPayment(registration)) {
            drawFit(g, "Demo payment - no real charge", 1077, 706, 254,
                    new Font("SansSerif", Font.BOLD, 15), 11, new Color(176, 48, 48));
        }

        g.dispose();
        return image;
    }

    private BufferedImage drawReceipt(Registration registration, Event event) throws IOException {
        final int w = 1000, h = 1414;
        BufferedImage image = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image);
        g.scale(2, 2);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(34, 32, 932, 1348, 24, 24));
        g.setColor(LINE);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new RoundRectangle2D.Double(34, 32, 932, 1348, 24, 24));
        logo(g, 78, 87, 1.15);
        tracked(g, "DEMO PAYMENT RECEIPT", 540, 141,
                new Font("SansSerif", Font.BOLD, 15), MUTED, 3);
        g.setColor(LINE);
        g.drawLine(75, 180, 924, 180);

        tracked(g, "EVENT REGISTRATION", 85, 241, new Font("SansSerif", Font.BOLD, 16), GOLD, 3);
        List<String> titleLines = wrappedTitle(g, clean(event.getTitle(), "Event"), 810, 51, 2);
        int y = 309;
        for (String text : titleLines) {
            drawFit(g, text, 84, y, 840, new Font("Serif", Font.BOLD, 51), 32, NAVY);
            y += 58;
        }
        drawFit(g, (event.getDate() == null ? "TBA" : event.getDate().format(EVENT_DATE))
                        + "  |  " + clean(event.getLocation(), "TBA"),
                87, y + 18, 838, new Font("SansSerif", Font.PLAIN, 19), 15, MUTED);
        int summaryY = Math.max(450, y + 58);
        g.setColor(new Color(232, 247, 245));
        g.fillRoundRect(80, summaryY, 838, 142, 18, 18);
        tracked(g, "TOTAL AMOUNT", 109, summaryY + 46,
                new Font("SansSerif", Font.BOLD, 16), MUTED, 2);
        drawFit(g, money(registration.getFinalPrice()), 106, summaryY + 106, 490,
                new Font("Serif", Font.BOLD, 56), 31, NAVY);
        g.setColor(new Color(229, 246, 234));
        g.fillRoundRect(692, summaryY + 48, 188, 50, 22, 22);
        drawFit(g, "CONFIRMED", 717, summaryY + 82, 156,
                new Font("SansSerif", Font.BOLD, 18), 13, GREEN);

        int detailY = summaryY + 195;
        tracked(g, "REGISTRATION DETAILS", 85, detailY,
                new Font("SansSerif", Font.BOLD, 16), GOLD, 2);
        detailRow(g, "Participant", clean(registration.getParticipantName(), "Participant"), detailY + 50);
        detailRow(g, "Email", clean(registration.getParticipantEmail(), "-"), detailY + 104);
        detailRow(g, "Ticket", clean(registration.getTicketTypeName(), "General Admission"), detailY + 158);
        detailRow(g, "Payment method", paymentMethod(registration.getPaymentMethod()), detailY + 212);
        LocalDateTime paidAt = registration.getRegisteredAt();
        detailRow(g, "Registration date", paidAt == null ? "-" : paidAt.format(PAYMENT_DATE), detailY + 266);
        detailRow(g, "Reference", reference(registration), detailY + 320);

        int priceY = detailY + 370;
        tracked(g, "PAYMENT SUMMARY", 85, priceY,
                new Font("SansSerif", Font.BOLD, 16), GOLD, 2);
        detailRow(g, "Ticket price", money(registration.getTicketPrice()), priceY + 50);
        if (registration.getCouponCode() != null && !registration.getCouponCode().isBlank()) {
            detailRow(g, "Promo code", registration.getCouponCode(), priceY + 104);
        }
        detailRow(g, "Discount", "- " + money(registration.getDiscountAmount()), priceY + 158);
        g.setColor(new Color(215, 223, 234));
        g.drawLine(88, priceY + 186, 904, priceY + 186);
        drawFit(g, "Final amount", 88, priceY + 230, 320,
                new Font("SansSerif", Font.BOLD, 23), 18, NAVY);
        rightAligned(g, money(registration.getFinalPrice()), 903, priceY + 230,
                new Font("Serif", Font.BOLD, 32), 22, NAVY, 340);

        g.setColor(LINE);
        g.drawLine(84, 1322, 916, 1322);
        drawFit(g, "Demo receipt: simulated payment, no real funds were collected.  |  Occavie", 86, 1353, 800,
                new Font("SansSerif", Font.PLAIN, 17), 13, MUTED);
        g.dispose();
        return image;
    }

    /** Draws the signed check-in QR code; returns false when the registration has no code yet. */
    private boolean drawQr(Graphics2D g, Registration registration, int x, int y, int size) {
        if (tokens == null || registration.getTicketCode() == null || registration.getTicketCode().isBlank()) return false;
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(
                    tokens.tokenFor(registration.getTicketCode()), com.google.zxing.BarcodeFormat.QR_CODE, 1, 1, hints);
            int modules = matrix.getWidth();
            double cell = (double) size / modules;
            g.setColor(NAVY);
            for (int row = 0; row < modules; row++) {
                for (int col = 0; col < modules; col++) {
                    if (matrix.get(col, row)) {
                        g.fill(new java.awt.geom.Rectangle2D.Double(x + col * cell, y + row * cell, Math.ceil(cell), Math.ceil(cell)));
                    }
                }
            }
            return true;
        } catch (com.google.zxing.WriterException ex) {
            return false;
        }
    }

    private Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private void logo(Graphics2D g, int x, int y, double scale) throws IOException {
        // Occavie mark (square) followed by the wordmark drawn in the document palette.
        int size = (int) (66 * scale);
        try (InputStream stream = getClass().getResourceAsStream("/static/images/occavie-mark.png")) {
            if (stream != null) {
                BufferedImage mark = ImageIO.read(stream);
                if (mark != null) {
                    g.drawImage(mark, x, y - (int) (14 * scale), size, size, null);
                }
            }
        }
        Font font = new Font("SansSerif", Font.BOLD, (int) (42 * scale));
        g.setFont(font);
        g.setColor(NAVY);
        int wordX = x + size + (int) (16 * scale);
        g.drawString("Occa", wordX, y + (int) (34 * scale));
        int tailX = wordX + g.getFontMetrics().stringWidth("Occa");
        g.setColor(GOLD);
        g.drawString("vie", tailX, y + (int) (34 * scale));
    }

    private void detailRow(Graphics2D g, String label, String value, int baseline) {
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g.drawString(label, 90, baseline);
        rightAligned(g, value, 905, baseline, new Font("SansSerif", Font.BOLD, 19), 13, NAVY, 575);
    }

    /** Booking status printed on the ticket. */
    private String bookingStatus(Registration registration) {
        return "Confirmed";
    }

    private boolean isDemoPayment(Registration registration) {
        return registration.getPaymentStatus() != null && registration.getPaymentStatus().startsWith("PAID");
    }

    private String money(BigDecimal value) {
        BigDecimal number = value == null ? BigDecimal.ZERO : value;
        return "BDT " + number.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String paymentMethod(String raw) {
        if (raw == null) return "-";
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "BKASH" -> "bKash";
            case "NAGAD" -> "Nagad";
            case "VISA" -> "Visa";
            default -> raw;
        };
    }

    private String reference(Registration registration) {
        String value = registration.getPaymentReference();
        if (value != null && !value.isBlank()) return value;
        // Never print the database id: show the short, opaque part of the ticket code instead.
        String code = registration.getTicketCode();
        return code == null || code.isBlank() ? "-" : "OV-" + code.substring(0, Math.min(8, code.length())).toUpperCase(Locale.ROOT);
    }

    private String clean(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
    }

    private void tracked(Graphics2D g, String text, int x, int y, Font font, Color color, int tracking) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics();
        for (int i = 0; i < text.length(); i++) {
            String letter = text.substring(i, i + 1);
            g.drawString(letter, x, y);
            x += metrics.stringWidth(letter) + tracking;
        }
    }

    private void drawFit(Graphics2D g, String text, int x, int baseline, int maxWidth,
                         Font base, int minSize, Color color) {
        if (text == null) text = "";
        Font font = base;
        g.setFont(font);
        while (font.getSize() > minSize && g.getFontMetrics().stringWidth(text) > maxWidth) {
            font = font.deriveFont((float)font.getSize() - 1);
            g.setFont(font);
        }
        if (g.getFontMetrics().stringWidth(text) > maxWidth) {
            while (text.length() > 1 && g.getFontMetrics().stringWidth(text + "...") > maxWidth)
                text = text.substring(0, text.length() - 1);
            text += "...";
        }
        g.setColor(color);
        g.drawString(text, x, baseline);
    }

    private void rightAligned(Graphics2D g, String value, int rightX, int baseline,
                              Font font, int minSize, Color color, int maxWidth) {
        if (value == null) value = "-";
        g.setFont(font);
        while (font.getSize() > minSize && g.getFontMetrics().stringWidth(value) > maxWidth) {
            font = font.deriveFont((float)font.getSize() - 1);
            g.setFont(font);
        }
        if (g.getFontMetrics().stringWidth(value) > maxWidth) {
            while (value.length() > 1 && g.getFontMetrics().stringWidth(value + "...") > maxWidth)
                value = value.substring(0, value.length() - 1);
            value += "...";
        }
        g.setColor(color);
        g.drawString(value, rightX - g.getFontMetrics().stringWidth(value), baseline);
    }

    private List<String> wrappedTitle(Graphics2D g, String title, int maxWidth, int size, int maxLines) {
        Font font = new Font("Serif", Font.BOLD, size);
        g.setFont(font);
        if (g.getFontMetrics().stringWidth(title) <= maxWidth) return List.of(title);
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String token : title.split("\\s+")) {
            String candidate = current.length() == 0 ? token : current + " " + token;
            if (g.getFontMetrics().stringWidth(candidate) <= maxWidth || current.length() == 0) {
                current.setLength(0);
                current.append(candidate);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(token);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        if (lines.size() > maxLines) {
            String remainder = String.join(" ", lines.subList(maxLines - 1, lines.size()));
            return List.of(lines.get(0), remainder);
        }
        return lines;
    }

    private void calendarIcon(Graphics2D g, int x, int y) {
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(3));
        g.drawRoundRect(x, y, 27, 26, 4, 4);
        g.drawLine(x, y + 8, x + 27, y + 8);
        g.drawLine(x + 8, y - 4, x + 8, y + 5);
        g.drawLine(x + 21, y - 4, x + 21, y + 5);
    }
    private void clockIcon(Graphics2D g, int x, int y) {
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(3));
        g.drawOval(x, y, 30, 30);
        g.drawLine(x + 15, y + 6, x + 15, y + 16);
        g.drawLine(x + 15, y + 16, x + 21, y + 20);
    }
    private void pinIcon(Graphics2D g, int x, int y) {
        g.setColor(GOLD);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x + 16, y + 33);
        path.curveTo(x + 9, y + 21, x + 1, y + 14, x + 7, y + 5);
        path.curveTo(x + 12, y - 4, x + 28, y - 4, x + 33, y + 6);
        path.curveTo(x + 39, y + 16, x + 21, y + 30, x + 16, y + 33);
        g.fill(path);
        g.setColor(Color.WHITE);
        g.fillOval(x + 13, y + 5, 9, 9);
    }
    private void ticketIcon(Graphics2D g, int x, int y) {
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(x, y, 37, 29, 5, 5);
        g.drawLine(x + 19, y + 2, x + 19, y + 7);
        g.drawLine(x + 19, y + 22, x + 19, y + 27);
    }
    private void documentIcon(Graphics2D g, int x, int y) {
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(x, y - 6, 28, 38, 3, 3);
        g.drawLine(x + 7, y + 10, x + 21, y + 10);
        g.drawLine(x + 7, y + 18, x + 21, y + 18);
    }
    private void bigTicketIcon(Graphics2D g, int x, int y) {
        g.setColor(new Color(11, 127, 126));
        g.setStroke(new BasicStroke(7));
        g.drawRoundRect(x, y, 103, 79, 14, 14);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                0f, new float[]{9f, 9f}, 0f));
        g.drawLine(x + 50, y + 9, x + 50, y + 70);
        g.setStroke(new BasicStroke(1.2f));
    }

    /** One-page PDF containing a high-quality image of the designed document. */
    private byte[] pdfWithImage(BufferedImage image, int pageWidth, int pageHeight) throws IOException {
        byte[] jpg;
        try (ByteArrayOutputStream jpgStream = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(jpgStream)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try {
                writer.setOutput(output);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(.94f);
                writer.write(null, new IIOImage(image, null, null), param);
                output.flush();
                jpg = jpgStream.toByteArray();
            } finally {
                writer.dispose();
            }
        }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        int[] offsets = new int[6];
        write(pdf, "%PDF-1.4\n%Occavie\n");
        object(pdf, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        object(pdf, offsets, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        object(pdf, offsets, 3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + pageWidth
                + " " + pageHeight + "] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>");
        String painting = "q\n" + pageWidth + " 0 0 " + pageHeight + " 0 0 cm\n/Im0 Do\nQ\n";
        byte[] content = painting.getBytes(StandardCharsets.US_ASCII);
        offsets[4] = pdf.size();
        write(pdf, "4 0 obj\n<< /Length " + content.length + " >>\nstream\n");
        pdf.write(content);
        write(pdf, "endstream\nendobj\n");
        offsets[5] = pdf.size();
        write(pdf, "5 0 obj\n<< /Type /XObject /Subtype /Image /Width " + image.getWidth()
                + " /Height " + image.getHeight()
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                + jpg.length + " >>\nstream\n");
        pdf.write(jpg);
        write(pdf, "\nendstream\nendobj\n");
        int xref = pdf.size();
        write(pdf, "xref\n0 6\n0000000000 65535 f \n");
        for (int i = 1; i < 6; i++)
            write(pdf, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
        write(pdf, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return pdf.toByteArray();
    }

    private void object(ByteArrayOutputStream stream, int[] offsets, int number, String dictionary) throws IOException {
        offsets[number] = stream.size();
        write(stream, number + " 0 obj\n" + dictionary + "\nendobj\n");
    }
    private void write(ByteArrayOutputStream stream, String text) throws IOException {
        stream.write(text.getBytes(StandardCharsets.US_ASCII));
    }
}
