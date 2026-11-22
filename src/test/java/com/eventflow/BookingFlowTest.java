package com.eventflow;

import com.eventflow.dto.PaymentQuote;
import com.eventflow.model.Coupon;
import com.eventflow.model.Event;
import com.eventflow.model.EventTicketType;
import com.eventflow.model.PaymentAttempt;
import com.eventflow.model.Registration;
import com.eventflow.model.User;
import com.eventflow.repository.CouponRepository;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.EventTicketTypeRepository;
import com.eventflow.repository.FeedbackRepository;
import com.eventflow.repository.PaymentAttemptRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.service.CouponService;
import com.eventflow.service.NotificationService;
import com.eventflow.service.PaymentFinalizationService;
import com.eventflow.service.RegistrationService;
import com.eventflow.service.TicketTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Service-level tests for the booking rules. Repositories are mocked, so no MongoDB is needed. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingFlowTest {

    @Mock CouponRepository couponRepository;
    @Mock RegistrationRepository registrationRepository;
    @Mock EventRepository eventRepository;
    @Mock EventTicketTypeRepository ticketTypeRepository;
    @Mock PaymentAttemptRepository paymentAttemptRepository;
    @Mock FeedbackRepository feedbackRepository;
    @Mock NotificationService notificationService;
    @Mock MongoTemplate mongoTemplate;

    com.eventflow.service.TicketTokenService tokens = new com.eventflow.service.TicketTokenService("test-secret");
    @Mock com.eventflow.repository.WaitlistRepository waitlistRepository;
    com.eventflow.service.WaitlistService waitlistService;
    CouponService couponService;
    TicketTypeService ticketTypeService;
    RegistrationService registrationService;
    PaymentFinalizationService paymentFinalization;

    Event freeEvent, paidEvent;
    User participant;

    @BeforeEach
    void setUp() {
        waitlistService = new com.eventflow.service.WaitlistService(waitlistRepository, registrationRepository, eventRepository, notificationService, mongoTemplate);
        couponService = new CouponService(couponRepository, registrationRepository);
        ticketTypeService = new TicketTypeService(ticketTypeRepository, registrationRepository);
        registrationService = new RegistrationService(registrationRepository, paymentAttemptRepository, mongoTemplate,
                eventRepository, feedbackRepository, notificationService, ticketTypeService, couponService, tokens, waitlistService);
        paymentFinalization = new PaymentFinalizationService(mongoTemplate, eventRepository, registrationRepository,
                paymentAttemptRepository, couponRepository, ticketTypeService, couponService, notificationService, tokens, waitlistService);

        freeEvent = Event.builder().id("free1").title("Free Meetup").organizerId("org1")
                .date(LocalDate.now().plusDays(5)).time(LocalTime.of(10, 0)).status("UPCOMING")
                .capacity(2).pricingType("FREE").build();
        paidEvent = Event.builder().id("paid1").title("Paid Summit").organizerId("org1")
                .date(LocalDate.now().plusDays(5)).time(LocalTime.of(10, 0)).status("UPCOMING")
                .capacity(100).pricingType("PAID").basePrice(new BigDecimal("1000")).build();
        participant = User.builder().id("p1").fullName("Pat Participant").email("pat@example.com").role("PARTICIPANT").build();

        when(mongoTemplate.updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.UpdateDefinition.class), org.mockito.ArgumentMatchers.eq(Event.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1L, 1L, null));
        when(eventRepository.findById("free1")).thenReturn(Optional.of(freeEvent));
        when(eventRepository.findById("paid1")).thenReturn(Optional.of(paidEvent));
        when(ticketTypeRepository.findByEventIdOrderBySortOrderAscNameAsc(anyString())).thenReturn(List.of());
        when(registrationRepository.save(any(Registration.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentAttemptRepository.save(any(PaymentAttempt.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Coupon coupon(String type, String value) {
        return Coupon.builder().id("c1").eventId("paid1").code("SAVE").discountType(type)
                .discountValue(new BigDecimal(value)).expiryDate(LocalDate.now().plusDays(10)).usageLimit(10).active(true).build();
    }

    // ---------------------------------------------------------------- coupons

    @Nested
    class Coupons {
        @Test
        void percentageDiscountIsCalculatedAndRounded() {
            when(couponRepository.findByEventIdAndCodeIgnoreCase("paid1", "SAVE")).thenReturn(Optional.of(coupon("PERCENT", "20")));
            var applied = couponService.apply(paidEvent, new BigDecimal("1000"), "save");
            assertEquals(new BigDecimal("200.00"), applied.discountAmount());
            assertEquals(new BigDecimal("800.00"), applied.finalPrice());
        }

        @Test
        void fixedDiscountIsCappedAtTicketPrice() {
            when(couponRepository.findByEventIdAndCodeIgnoreCase("paid1", "SAVE")).thenReturn(Optional.of(coupon("FIXED", "5000")));
            var applied = couponService.apply(paidEvent, new BigDecimal("1000"), "SAVE");
            assertEquals(new BigDecimal("1000.00"), applied.discountAmount());
            assertEquals(new BigDecimal("0.00"), applied.finalPrice());
        }

        @Test
        void blankCodeMeansNoDiscount() {
            var applied = couponService.apply(paidEvent, new BigDecimal("750"), "  ");
            assertEquals(new BigDecimal("0.00"), applied.discountAmount());
            assertEquals(new BigDecimal("750.00"), applied.finalPrice());
            assertNull(applied.couponId());
        }

        @Test
        void expiredPausedUnknownAndExhaustedCouponsAreRejected() {
            Coupon expired = coupon("PERCENT", "10"); expired.setExpiryDate(LocalDate.now().minusDays(1));
            when(couponRepository.findByEventIdAndCodeIgnoreCase("paid1", "OLD")).thenReturn(Optional.of(expired));
            assertEquals("This coupon has expired.", assertThrows(IllegalArgumentException.class,
                    () -> couponService.apply(paidEvent, new BigDecimal("1000"), "OLD")).getMessage());

            Coupon paused = coupon("PERCENT", "10"); paused.setActive(false);
            when(couponRepository.findByEventIdAndCodeIgnoreCase("paid1", "PAUSED")).thenReturn(Optional.of(paused));
            assertEquals("This coupon is currently paused.", assertThrows(IllegalArgumentException.class,
                    () -> couponService.apply(paidEvent, new BigDecimal("1000"), "PAUSED")).getMessage());

            Coupon used = coupon("PERCENT", "10"); used.setUsageLimit(1);
            when(couponRepository.findByEventIdAndCodeIgnoreCase("paid1", "USED")).thenReturn(Optional.of(used));
            when(registrationRepository.countByCouponId("c1")).thenReturn(1L);
            assertEquals("This coupon has reached its usage limit.", assertThrows(IllegalArgumentException.class,
                    () -> couponService.apply(paidEvent, new BigDecimal("1000"), "USED")).getMessage());

            assertEquals("Coupon code is invalid for this event.", assertThrows(IllegalArgumentException.class,
                    () -> couponService.apply(paidEvent, new BigDecimal("1000"), "NOPE")).getMessage());
        }

        @Test
        void couponsCannotBeAppliedToFreeTickets() {
            assertThrows(IllegalArgumentException.class, () -> couponService.apply(freeEvent, BigDecimal.ZERO, "SAVE"));
        }
    }

    // ---------------------------------------------------------------- tickets

    @Nested
    class Tickets {
        private EventTicketType ticket(String id, String name, String price, int capacity) {
            return EventTicketType.builder().id(id).eventId("paid1").name(name).price(new BigDecimal(price))
                    .capacity(capacity).active(true).build();
        }

        @Test
        void soldOutTicketTypeIsRejected() {
            when(ticketTypeRepository.findByEventIdOrderBySortOrderAscNameAsc("paid1")).thenReturn(List.of(ticket("t1", "VIP", "2000", 5)));
            when(registrationRepository.countByTicketTypeId("t1")).thenReturn(5L);
            var ex = assertThrows(IllegalArgumentException.class, () -> ticketTypeService.resolveForRegistration(paidEvent, "t1"));
            assertEquals("VIP tickets are sold out.", ex.getMessage());
        }

        @Test
        void availableTicketTypeIsResolvedWithItsPrice() {
            when(ticketTypeRepository.findByEventIdOrderBySortOrderAscNameAsc("paid1")).thenReturn(List.of(ticket("t1", "Student", "400", 5), ticket("t2", "VIP", "2000", 5)));
            var selection = ticketTypeService.resolveForRegistration(paidEvent, "t1");
            assertEquals("Student", selection.ticketTypeName());
            assertEquals(new BigDecimal("400"), selection.price());
        }

        @Test
        void multipleTicketTypesRequireAnExplicitChoice() {
            when(ticketTypeRepository.findByEventIdOrderBySortOrderAscNameAsc("paid1")).thenReturn(List.of(ticket("t1", "Student", "400", 5), ticket("t2", "VIP", "2000", 5)));
            assertThrows(IllegalArgumentException.class, () -> ticketTypeService.resolveForRegistration(paidEvent, null));
            assertThrows(IllegalArgumentException.class, () -> ticketTypeService.resolveForRegistration(paidEvent, "unknown"));
        }
    }

    // ---------------------------------------------------------------- free registration

    @Nested
    class FreeRegistration {
        @Test
        void confirmedRegistrationIsSavedAndBothSidesAreNotified() {
            Registration saved = registrationService.register("free1", participant);
            assertEquals("NOT_REQUIRED", saved.getPaymentStatus());
            assertEquals("p1", saved.getParticipantId());
            assertNotNull(saved.getTicketCode(), "every confirmed registration gets a QR code");
            assertEquals("General Admission", saved.getTicketTypeName());
            verify(notificationService).notifyUser(org.mockito.ArgumentMatchers.eq("org1"), anyString(), anyString());
            verify(notificationService).notify(org.mockito.ArgumentMatchers.eq(com.eventflow.service.NotificationType.REGISTRATION_CONFIRMED), org.mockito.ArgumentMatchers.eq("p1"), anyString(), any(), org.mockito.ArgumentMatchers.contains("confirmed"), anyString(), anyString(), any());
        }

        @Test
        void duplicateRegistrationIsRejected() {
            when(registrationRepository.existsByEventIdAndParticipantId("free1", "p1")).thenReturn(true);
            var ex = assertThrows(IllegalArgumentException.class, () -> registrationService.register("free1", participant));
            assertEquals("You are already registered for this event.", ex.getMessage());
            verify(registrationRepository, never()).save(any());
        }

        @Test
        void fullEventIsRejected() {
            when(registrationRepository.countByEventId("free1")).thenReturn(2L);
            var ex = assertThrows(IllegalArgumentException.class, () -> registrationService.register("free1", participant));
            assertEquals("This event is already full.", ex.getMessage());
        }

        @Test
        void pastEventIsClosed() {
            freeEvent.setDate(LocalDate.now().minusDays(1));
            assertThrows(IllegalArgumentException.class, () -> registrationService.register("free1", participant));
        }

        @Test
        void paidTicketsCannotSkipCheckout() {
            var ex = assertThrows(IllegalArgumentException.class, () -> registrationService.register("paid1", participant));
            assertEquals("Paid tickets must be booked through checkout.", ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- simulated paid booking

    @Nested
    class DemoPayment {
        private PaymentQuote quote() {
            return new PaymentQuote("paid1", null, "General Admission", new BigDecimal("1000"), null, null,
                    BigDecimal.ZERO, new BigDecimal("1000"));
        }

        @Test
        void confirmedDemoBookingIsMarkedAsSimulated() {
            PaymentAttempt attempt = paymentFinalization.completeDemo(participant, quote(), "BKASH");
            assertEquals("PAID", attempt.getStatus());
            assertTrue(attempt.getTransactionId().startsWith("OV"));
            ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
            verify(registrationRepository).save(saved.capture());
            assertEquals("PAID_DEMO", saved.getValue().getPaymentStatus());
            assertEquals(0, new BigDecimal("1000").compareTo(saved.getValue().getFinalPrice()));
        }

        @Test
        void duplicateBookingIsRejected() {
            when(registrationRepository.existsByEventIdAndParticipantId("paid1", "p1")).thenReturn(true);
            assertThrows(IllegalArgumentException.class, () -> paymentFinalization.completeDemo(participant, quote(), "VISA"));
            verify(registrationRepository, never()).save(any());
        }

        @Test
        void soldOutEventIsRejected() {
            paidEvent.setCapacity(1);
            when(registrationRepository.countByEventId("paid1")).thenReturn(1L);
            assertThrows(IllegalArgumentException.class, () -> paymentFinalization.completeDemo(participant, quote(), "NAGAD"));
        }

        @Test
        void soldOutTicketTypeIsRejected() {
            EventTicketType vip = EventTicketType.builder().id("t1").eventId("paid1").name("VIP")
                    .price(new BigDecimal("2000")).capacity(3).active(true).build();
            when(ticketTypeRepository.findByEventIdOrderBySortOrderAscNameAsc("paid1")).thenReturn(List.of(vip));
            when(registrationRepository.countByTicketTypeId("t1")).thenReturn(3L);
            PaymentQuote vipQuote = new PaymentQuote("paid1", "t1", "VIP", new BigDecimal("2000"), null, null,
                    BigDecimal.ZERO, new BigDecimal("2000"));
            assertThrows(IllegalArgumentException.class, () -> paymentFinalization.completeDemo(participant, vipQuote, "VISA"));
        }

        @Test
        void couponDiscountIsStoredWithTheBooking() {
            Coupon c = coupon("PERCENT", "25");
            when(couponRepository.findByEventIdAndCodeIgnoreCase("paid1", "SAVE")).thenReturn(Optional.of(c));
            when(couponRepository.findById("c1")).thenReturn(Optional.of(c));
            PaymentQuote q = new PaymentQuote("paid1", null, "General Admission", new BigDecimal("1000"), "c1", "SAVE",
                    new BigDecimal("250.00"), new BigDecimal("750.00"));
            paymentFinalization.completeDemo(participant, q, "BKASH");
            ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
            verify(registrationRepository).save(saved.capture());
            assertEquals(new BigDecimal("250.00"), saved.getValue().getDiscountAmount());
            assertEquals(new BigDecimal("750.00"), saved.getValue().getFinalPrice());
            assertEquals("SAVE", saved.getValue().getCouponCode());
        }

        @Test
        void organizersCannotBook() {
            User organizer = User.builder().id("o2").fullName("Org").email("o@example.com").role("ORGANIZER").build();
            assertThrows(IllegalArgumentException.class, () -> paymentFinalization.completeDemo(organizer, quote(), "VISA"));
        }
    }
}
