package com.eventflow;

import com.eventflow.model.Event;
import com.eventflow.model.User;
import com.eventflow.repository.EventImageRepository;
import com.eventflow.repository.EventRepository;
import com.eventflow.service.CouponService;
import com.eventflow.service.EventGalleryService;
import com.eventflow.service.EventProgramService;
import com.eventflow.service.EventRules;
import com.eventflow.service.EventService;
import com.eventflow.service.NotificationService;
import com.eventflow.service.NotificationType;
import com.eventflow.service.RegistrationService;
import com.eventflow.service.TicketTypeService;
import com.eventflow.service.WaitlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Draft -> Pending approval -> Published / Rejected transitions, permissions, and the approval-off behaviour. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalWorkflowTest {
    @Mock EventRepository eventRepository;
    @Mock EventImageRepository imageRepository;
    @Mock RegistrationService registrationService;
    @Mock NotificationService notifications;
    @Mock EventProgramService programService;
    @Mock TicketTypeService ticketTypeService;
    @Mock CouponService couponService;
    @Mock EventGalleryService galleryService;
    @Mock WaitlistService waitlistService;

    EventService service;
    User owner, stranger, admin, participant;
    Event draft;

    @BeforeEach
    void setUp() {
        service = new EventService(eventRepository, imageRepository, registrationService, notifications, programService,
                ticketTypeService, couponService, galleryService, waitlistService);
        owner = User.builder().id("org1").fullName("Olive").role("ORGANIZER").build();
        stranger = User.builder().id("org2").fullName("Sam").role("ORGANIZER").build();
        admin = User.builder().id("a1").fullName("Ada").role("ADMIN").build();
        participant = User.builder().id("p1").fullName("Pat").role("PARTICIPANT").build();
        draft = Event.builder().id("d1").title("Launch Night").category("Business").location("City Hall")
                .description("An evening to celebrate the launch with the community.").capacity(80).status("DRAFT")
                .organizerId("org1").date(LocalDate.now().plusDays(10)).time(LocalTime.of(18, 0)).build();
        when(eventRepository.findById("d1")).thenReturn(Optional.of(draft));
        when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void approvalOn() { ReflectionTestUtils.setField(service, "approvalRequired", true); }

    // ---------------------------------------------------------------- approval enabled

    @Test
    void fullHappyPathDraftPendingPublished() {
        approvalOn();
        assertEquals("PENDING_APPROVAL", service.submitForApproval("d1", owner).getStatus());
        assertNotNull(draft.getSubmittedAt());
        Event approved = service.approve("d1", admin);
        assertEquals("UPCOMING", approved.getStatus());
        assertEquals("a1", approved.getReviewedBy());
        assertTrue(EventRules.isPublished(approved) && EventRules.bookable(approved));
        verify(notifications).notify(eq(NotificationType.APPROVAL_UPDATE), eq("org1"), eq("d1"), any(), contains("approved"), anyString(), anyString(), any());
    }

    private static String contains(String part) { return org.mockito.ArgumentMatchers.contains(part); }

    @Test
    void directPublishIsBlockedWhenApprovalIsRequired() {
        approvalOn();
        var ex = assertThrows(IllegalArgumentException.class, () -> service.publish("d1", owner));
        assertTrue(ex.getMessage().contains("approval"));
        assertEquals("DRAFT", draft.getStatus());
    }

    @Test
    void pendingAndRejectedEventsAreNeverPublicOrBookable() {
        approvalOn();
        service.submitForApproval("d1", owner);
        assertFalse(EventRules.isPublished(draft));
        assertFalse(EventRules.bookable(draft));
        assertFalse(service.isUpcoming(draft));
        assertEquals("PENDING_APPROVAL", EventRules.status(draft));
        service.reject("d1", "Please add a venue address", admin);
        assertFalse(EventRules.isPublished(draft));
        assertEquals("REJECTED", EventRules.status(draft));
    }

    @Test
    void onlyAdministratorsMayApproveOrReject() {
        approvalOn();
        service.submitForApproval("d1", owner);
        for (User notAdmin : new User[]{owner, stranger, participant, null}) {
            assertThrows(IllegalArgumentException.class, () -> service.approve("d1", notAdmin));
            assertThrows(IllegalArgumentException.class, () -> service.reject("d1", "Not suitable for the platform", notAdmin));
        }
        assertEquals("PENDING_APPROVAL", draft.getStatus(), "nothing changed");
    }

    @Test
    void rejectionNeedsAReasonThenOrganizerCanFixAndResubmit() {
        approvalOn();
        service.submitForApproval("d1", owner);
        assertThrows(IllegalArgumentException.class, () -> service.reject("d1", "", admin));
        assertThrows(IllegalArgumentException.class, () -> service.reject("d1", "no", admin));
        Event rejected = service.reject("d1", "Description is too vague", admin);
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("Description is too vague", rejected.getApprovalNote());
        verify(notifications).notify(eq(NotificationType.APPROVAL_UPDATE), eq("org1"), eq("d1"), any(), contains("Description is too vague"), anyString(), anyString(), any());
        assertTrue(EventRules.isEditableDraft(rejected), "the organizer can edit a rejected event");
        assertEquals("PENDING_APPROVAL", service.submitForApproval("d1", owner).getStatus());
        assertNull(draft.getApprovalNote(), "old reason cleared on resubmission");
    }

    @Test
    void invalidTransitionsAreRejected() {
        approvalOn();
        assertThrows(IllegalArgumentException.class, () -> service.approve("d1", admin), "a draft is not awaiting approval");
        assertThrows(IllegalArgumentException.class, () -> service.reject("d1", "Because reasons", admin));
        assertThrows(IllegalArgumentException.class, () -> service.withdraw("d1", owner), "only pending events can be withdrawn");
        service.submitForApproval("d1", owner);
        assertThrows(IllegalArgumentException.class, () -> service.submitForApproval("d1", owner), "already pending");
        service.approve("d1", admin);
        assertThrows(IllegalArgumentException.class, () -> service.approve("d1", admin), "already published");
        assertThrows(IllegalArgumentException.class, () -> service.updateDraft("d1", new com.eventflow.dto.EventDraftForm("New title", null, null, null, null, null, null, null, null, null, null, null), owner),
                "published events are not drafts");
    }

    @Test
    void organizerOwnershipAndReadinessAreEnforcedOnSubmit() {
        approvalOn();
        assertThrows(IllegalArgumentException.class, () -> service.submitForApproval("d1", stranger));
        draft.setLocation(null);
        var ex = assertThrows(IllegalArgumentException.class, () -> service.submitForApproval("d1", owner));
        assertTrue(ex.getMessage().contains("Venue"));
        assertEquals("DRAFT", draft.getStatus());
    }

    @Test
    void organizerCanWithdrawAndEditAgain() {
        approvalOn();
        service.submitForApproval("d1", owner);
        assertThrows(IllegalArgumentException.class, () -> service.withdraw("d1", stranger));
        assertEquals("DRAFT", service.withdraw("d1", owner).getStatus());
        assertTrue(EventRules.isEditableDraft(draft));
    }

    // ---------------------------------------------------------------- approval disabled: existing workflow preserved

    @Test
    void withApprovalOffOrganizersPublishDirectlyAsBefore() {
        assertFalse(service.isApprovalRequired());
        assertEquals("UPCOMING", service.publish("d1", owner).getStatus());
    }

    @Test
    void withApprovalOffSubmittingIsNotAllowed() {
        var ex = assertThrows(IllegalArgumentException.class, () -> service.submitForApproval("d1", owner));
        assertTrue(ex.getMessage().contains("not enabled"));
        assertEquals("DRAFT", draft.getStatus());
    }

    @Test
    void eventStaysPublishableFromRejectedWhenApprovalIsTurnedOffLater() {
        draft.setStatus("REJECTED");
        assertEquals("UPCOMING", service.publish("d1", owner).getStatus());
    }
}
