package com.eventflow;

import com.eventflow.model.PaymentAttempt;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.PaymentAttemptRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.service.CouponService;
import com.eventflow.service.PaymentFinalizationService;
import com.eventflow.service.PaymentService;
import com.eventflow.service.TicketTypeService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Regression: the reference generated at payment time (OV + 25 hex) must be accepted when the result page loads. */
class PaymentReferenceTest {
    private PaymentService service(PaymentAttemptRepository repo) {
        return new PaymentService(Mockito.mock(EventRepository.class), Mockito.mock(RegistrationRepository.class), repo,
                Mockito.mock(TicketTypeService.class), Mockito.mock(CouponService.class), Mockito.mock(PaymentFinalizationService.class));
    }

    @Test
    void currentAndLegacyReferencesAreAccepted() {
        PaymentAttemptRepository repo = Mockito.mock(PaymentAttemptRepository.class);
        Mockito.when(repo.findByTransactionId(Mockito.anyString())).thenReturn(Optional.of(new PaymentAttempt()));
        PaymentService s = service(repo);
        String generated = "OV" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25).toUpperCase();
        assertNotNull(s.require(generated));
        assertNotNull(s.require("EFDCB26F6B825843CE8EEA029BA"));
    }

    @Test
    void malformedReferencesAreRejected() {
        PaymentService s = service(Mockito.mock(PaymentAttemptRepository.class));
        assertThrows(IllegalArgumentException.class, () -> s.require("XX123"));
        assertThrows(IllegalArgumentException.class, () -> s.require(null));
        assertThrows(IllegalArgumentException.class, () -> s.require("OV" + "g".repeat(25)));
    }
}
