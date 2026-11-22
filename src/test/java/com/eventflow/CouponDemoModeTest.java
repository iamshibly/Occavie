package com.eventflow;

import com.eventflow.model.Event;
import com.eventflow.repository.CouponRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.service.CouponService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CouponDemoModeTest {
    private Event paidEvent() {
        Event e = new Event();
        e.setId("e1"); e.setPricingType("PAID"); e.setBasePrice(new BigDecimal("350")); e.setDate(LocalDate.now().plusDays(10));
        return e;
    }

    private CouponService service(boolean acceptAny) {
        CouponRepository repo = Mockito.mock(CouponRepository.class);
        Mockito.when(repo.findByEventIdAndCodeIgnoreCase(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        CouponService s = new CouponService(repo, Mockito.mock(RegistrationRepository.class));
        ReflectionTestUtils.setField(s, "acceptAnyCode", acceptAny);
        return s;
    }

    @Test
    void demoModeAcceptsAnyCodeWithTenPercent() {
        var applied = service(true).apply(paidEvent(), new BigDecimal("350"), "random-123");
        assertEquals("RANDOM-123", applied.couponCode());
        assertNull(applied.couponId());
        assertEquals(0, new BigDecimal("35.00").compareTo(applied.discountAmount()));
        assertEquals(0, new BigDecimal("315.00").compareTo(applied.finalPrice()));
    }

    @Test
    void strictModeStillRejectsUnknownCodes() {
        assertThrows(IllegalArgumentException.class, () -> service(false).apply(paidEvent(), new BigDecimal("350"), "NOPE"));
    }

    @Test
    void blankCodeMeansNoCoupon() {
        var applied = service(true).apply(paidEvent(), new BigDecimal("350"), "  ");
        assertNull(applied.couponCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(applied.discountAmount()));
    }
}
