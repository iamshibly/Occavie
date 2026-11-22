package com.eventflow;

import com.eventflow.config.ProductionSetup;
import com.eventflow.model.Event;
import com.eventflow.service.ImageUpload;
import com.eventflow.service.SlidingWindowRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class SecurityHardeningTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final String GOOD = "a-random-secret-of-thirty-chars-1";
    private static final String GOOD2 = "another-random-secret-30-chars-2";

    @Test
    void rateLimiterBlocksAfterLimitAndRecoversAfterWindow() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        for (int i = 0; i < 3; i++) assertEquals(0, limiter.tryAcquire("ip", 3, 60_000, 1_000 + i));
        assertTrue(limiter.tryAcquire("ip", 3, 60_000, 2_000) > 0, "4th attempt in the window is refused with a retry-after");
        assertEquals(0, limiter.tryAcquire("other", 3, 60_000, 2_000), "keys are independent");
        assertEquals(0, limiter.tryAcquire("ip", 3, 60_000, 70_000), "allowed again once the window has passed");
    }

    @Test
    void productionRefusesUnsafeSecrets() {
        assertTrue(ProductionSetup.secretProblems(GOOD, GOOD2, "mongodb://x", "https://occavie.example").isEmpty());
        assertFalse(ProductionSetup.secretProblems("", GOOD2, "mongodb://x", "https://a.b").isEmpty());
        assertFalse(ProductionSetup.secretProblems("occavie-local-development-key-change", GOOD2, "mongodb://x", "https://a.b").isEmpty());
        assertFalse(ProductionSetup.secretProblems(GOOD, GOOD, "mongodb://x", "https://a.b").isEmpty(), "ticket secret must differ");
        assertFalse(ProductionSetup.secretProblems(GOOD, GOOD2, "", "https://a.b").isEmpty());
        assertFalse(ProductionSetup.secretProblems(GOOD, GOOD2, "mongodb://x", "http://a.b").isEmpty(), "public URL must be https");
        assertFalse(ProductionSetup.secretProblems("short", GOOD2, "mongodb://x", "https://a.b").isEmpty());
    }

    @Test
    void imageUploadChecksContentNotJustDeclaredType() {
        assertDoesNotThrow(() -> ImageUpload.verify(new MockMultipartFile("f", "a.png", "image/png", PNG), "Photo"));
        MockMultipartFile fake = new MockMultipartFile("f", "a.png", "image/png", "<script>alert(1)</script>".getBytes());
        assertThrows(RuntimeException.class, () -> ImageUpload.verify(fake, "Photo"));
        MockMultipartFile mismatch = new MockMultipartFile("f", "a.jpg", "image/jpeg", PNG);
        assertThrows(RuntimeException.class, () -> ImageUpload.verify(mismatch, "Photo"));
        MockMultipartFile big = new MockMultipartFile("f", "a.png", "image/png", new byte[(int) ImageUpload.MAX_BYTES + 1]);
        assertThrows(RuntimeException.class, () -> ImageUpload.verify(big, "Photo"));
    }

    @Test
    void eventJsonLdEscapesHostileText() throws Exception {
        Event e = new Event();
        e.setId("1"); e.setTitle("</script><script>alert(1)</script> \"q\""); e.setLocation("Dhaka");
        Method m = com.eventflow.controller.MainController.class.getDeclaredMethod("eventJsonLd", Event.class, String.class, String.class);
        m.setAccessible(true);
        String json = (String) m.invoke(null, e, "https://x.test", "/images/og-default.png");
        assertFalse(json.contains("</script>"));
        assertFalse(json.contains("<"));
        assertTrue(json.contains("\\u003c/script\\u003e"));
        assertTrue(json.contains("\\\"q\\\""));
    }

    @Test
    void csvNeutralisesFormulasAndQuotesSpecialCharacters() {
        assertEquals("'=HYPERLINK(\"x\")".replace("\"", "\"\""), com.eventflow.service.CsvExport.cell("=HYPERLINK(\"x\")").replaceAll("^\"|\"$", ""));
        assertEquals("'+1", com.eventflow.service.CsvExport.cell("+1"));
        assertEquals("\"a,b\"", com.eventflow.service.CsvExport.cell("a,b"));
        assertEquals("", com.eventflow.service.CsvExport.cell(null));
        assertEquals("a,b\r\n", com.eventflow.service.CsvExport.row(java.util.List.of("a", "b")));
    }
}
