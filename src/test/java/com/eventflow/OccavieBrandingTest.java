package com.eventflow;

import com.eventflow.config.SecurityConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Static checks for the Occavie rebrand and front-end asset integrity. No Spring context or MongoDB needed. */
class OccavieBrandingTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static Stream<Path> files(String dir, String suffix) throws IOException {
        return Files.walk(RESOURCES.resolve(dir)).filter(p -> p.toString().endsWith(suffix));
    }

    @Test
    void noUserFacingEventFlowBrandingRemainsInTemplatesOrScripts() throws IOException {
        for (Path p : (Iterable<Path>) files("templates", ".html")::iterator) {
            assertFalse(Files.readString(p).toLowerCase().replace("@eventflow.com", "").replace("eventflowpublictheme", "")
                    .contains("eventflow"), "Legacy brand in " + p);
        }
        for (Path p : (Iterable<Path>) files("static/js", ".js")::iterator) {
            assertFalse(Files.readString(p).contains("EventFlow"), "Legacy brand in " + p);
        }
    }

    @Test
    void sharedHeadFragmentCarriesTaglineAndFavicons() throws IOException {
        String fragments = Files.readString(RESOURCES.resolve("templates/fragments.html"));
        assertTrue(fragments.contains("Make every moment count"));
        assertTrue(fragments.contains("favicon-32.png"));
        assertTrue(Files.exists(RESOURCES.resolve("static/favicon.ico")));
        assertTrue(Files.exists(RESOURCES.resolve("static/images/occavie-mark.png")));
    }

    @Test
    void everyLocalStaticAssetReferencedByTemplatesExists() throws IOException {
        Pattern ref = Pattern.compile("@\\{/((?:css|js|images)/[A-Za-z0-9._-]+)");
        for (Path p : (Iterable<Path>) files("templates", ".html")::iterator) {
            Matcher m = ref.matcher(Files.readString(p));
            while (m.find()) {
                assertTrue(Files.exists(RESOURCES.resolve("static/" + m.group(1))),
                        p.getFileName() + " references missing asset " + m.group(1));
            }
        }
    }

    @Test
    void roleAreasStayProtectedInSecurityConfig() throws IOException {
        String config = Files.readString(Path.of("src/main/java/com/eventflow/config/SecurityConfig.java"));
        assertTrue(config.contains("\"/admin/**\").hasRole(\"ADMIN\")"));
        assertTrue(config.contains("\"/organizer/**\").hasRole(\"ORGANIZER\")"));
        assertTrue(config.contains("\"/participant/**\").hasRole(\"PARTICIPANT\")"));
        assertTrue(config.contains(".anyRequest().authenticated()"));
    }

    @Test
    void safeRedirectRejectsOpenRedirects() {
        assertEquals("/events/1#tickets", SecurityConfig.safeRedirect("/events/1#tickets"));
        assertNull(SecurityConfig.safeRedirect("//evil.com"));
        assertNull(SecurityConfig.safeRedirect("https://evil.com"));
        assertNull(SecurityConfig.safeRedirect("/\\evil.com"));
        assertNull(SecurityConfig.safeRedirect("/a/../b"));
        assertNull(SecurityConfig.safeRedirect(null));
    }

    @Test
    void simulatedPaymentsAreAlwaysLabelledAsDemo() throws IOException {
        String checkout = Files.readString(RESOURCES.resolve("templates/payment-checkout.html"));
        assertTrue(checkout.contains("Demo checkout") && checkout.contains("No real payment"));
        String result = Files.readString(RESOURCES.resolve("templates/payment-result.html"));
        assertTrue(result.contains("simulated payment"));
        String dashboard = Files.readString(RESOURCES.resolve("templates/dashboard.html"));
        assertTrue(dashboard.contains("Simulated ticket value") && dashboard.contains("not revenue"));
        String service = Files.readString(Path.of("src/main/java/com/eventflow/service/RegistrationDocumentService.java"));
        assertTrue(service.contains("Demo payment - no real charge") && service.contains("DEMO PAYMENT RECEIPT"));
    }
}
