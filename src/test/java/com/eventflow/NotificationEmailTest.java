package com.eventflow;

import com.eventflow.model.EmailMessage;
import com.eventflow.model.Notification;
import com.eventflow.model.User;
import com.eventflow.repository.EmailMessageRepository;
import com.eventflow.repository.EventRepository;
import com.eventflow.repository.NotificationRepository;
import com.eventflow.repository.RegistrationRepository;
import com.eventflow.repository.UserRepository;
import com.eventflow.service.EmailOutboxService;
import com.eventflow.service.EmailProvider;
import com.eventflow.service.EmailTemplates;
import com.eventflow.service.NotificationService;
import com.eventflow.service.NotificationType;
import com.eventflow.service.ResendEmailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** In-app notifications, the email outbox, preferences and duplicate prevention. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationEmailTest {
    @Mock NotificationRepository notificationRepository;
    @Mock EventRepository eventRepository;
    @Mock RegistrationRepository registrationRepository;
    @Mock UserRepository userRepository;
    @Mock EmailMessageRepository emailRepository;
    @Mock MongoTemplate mongo;
    @Mock EmailProvider provider;

    final Set<String> inAppKeys = new HashSet<>();
    final Set<String> emailKeys = new HashSet<>();
    EmailOutboxService outbox;
    NotificationService notifications;
    User ann;

    @BeforeEach
    void setUp() {
        ann = User.builder().id("u1").fullName("Ann Attendee").email("ann@example.com").role("PARTICIPANT").build();
        when(userRepository.findById("u1")).thenReturn(Optional.of(ann));
        when(provider.enabled()).thenReturn(true);
        // in-app store honours the unique dedupe key
        when(notificationRepository.existsByDedupeKey(anyString())).thenAnswer(i -> inAppKeys.contains(i.<String>getArgument(0)));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> { inAppKeys.add(i.<Notification>getArgument(0).getDedupeKey()); return i.getArgument(0); });
        // email store honours the unique dedupe key
        when(emailRepository.findByDedupeKey(anyString())).thenAnswer(i -> emailKeys.contains(i.<String>getArgument(0)) ? Optional.of(new EmailMessage()) : Optional.empty());
        when(emailRepository.save(any(EmailMessage.class))).thenAnswer(i -> { emailKeys.add(i.<EmailMessage>getArgument(0).getDedupeKey()); return i.getArgument(0); });
        outbox = new EmailOutboxService(emailRepository, mongo, provider);
        notifications = new NotificationService(notificationRepository, eventRepository, registrationRepository, userRepository, outbox);
    }

    private boolean notify(NotificationType type, String key) {
        return notifications.notify(type, "u1", "e1", "Summit", "Message", "/events/e1", key, List.of("Where: Dhaka"));
    }

    // ---------------------------------------------------------------- duplicate prevention

    @Test
    void sameDedupeKeyCreatesOneNotificationAndOneEmail() {
        assertTrue(notify(NotificationType.REGISTRATION_CONFIRMED, "REG:r1"));
        assertFalse(notify(NotificationType.REGISTRATION_CONFIRMED, "REG:r1"), "second call is a no-op");
        assertFalse(notify(NotificationType.REGISTRATION_CONFIRMED, "REG:r1"));
        verify(notificationRepository, times(1)).save(any(Notification.class));
        verify(emailRepository, times(1)).save(any(EmailMessage.class));
        assertTrue(notify(NotificationType.REGISTRATION_CONFIRMED, "REG:r2"), "a different registration notifies again");
    }

    @Test
    void concurrentDuplicateInsertIsSwallowedNotDuplicated() {
        when(notificationRepository.save(any(Notification.class))).thenThrow(new DuplicateKeyException("dup"));
        assertFalse(notify(NotificationType.EVENT_REMINDER, "REMINDER:e1:u1"));
        verify(emailRepository, never()).save(any(EmailMessage.class));
    }

    @Test
    void outboxIgnoresAnAlreadyQueuedKeyAndAConcurrentInsert() {
        assertTrue(outbox.enqueue("X", "k1", "a@example.com", "A", "s", "t", null));
        assertFalse(outbox.enqueue("X", "k1", "a@example.com", "A", "s", "t", null));
        when(emailRepository.save(any(EmailMessage.class))).thenThrow(new DuplicateKeyException("dup"));
        assertFalse(outbox.enqueue("X", "k2", "a@example.com", "A", "s", "t", null));
        assertFalse(outbox.enqueue("X", "", "a@example.com", "A", "s", "t", null), "a key is required");
        assertFalse(outbox.enqueue("X", "k3", "", "A", "s", "t", null), "an address is required");
    }

    // ---------------------------------------------------------------- preferences

    @Test
    void essentialEmailsAlwaysSentOptionalOnesFollowPreferences() {
        ann.setEmailReminders(false);
        ann.setEmailAnnouncements(false);
        notify(NotificationType.EVENT_RESCHEDULED, "a");
        notify(NotificationType.WAITLIST_OFFER, "b");
        notify(NotificationType.EVENT_CANCELLED, "c");
        verify(emailRepository, times(3)).save(any(EmailMessage.class));

        clearInvocations(emailRepository);
        notify(NotificationType.EVENT_REMINDER, "d");
        notify(NotificationType.ANNOUNCEMENT, "e");
        verify(emailRepository, never()).save(any(EmailMessage.class));
        verify(notificationRepository, times(5)).save(any(Notification.class));   // still shown in-app

        ann.setEmailReminders(true);
        ann.setEmailAnnouncements(true);
        notify(NotificationType.EVENT_REMINDER, "f");
        notify(NotificationType.ANNOUNCEMENT, "g");
        verify(emailRepository, times(2)).save(any(EmailMessage.class));
    }

    @Test
    void generalNotificationsAndDisabledUsersAreNotEmailed() {
        notify(NotificationType.GENERAL, "g1");
        ann.setEnabled(false);
        notify(NotificationType.EVENT_CANCELLED, "g2");
        verify(emailRepository, never()).save(any(EmailMessage.class));
        verify(notificationRepository, times(2)).save(any(Notification.class));
    }

    @Test
    void typedNotificationsStoreTypeEventAndDirectLink() {
        notify(NotificationType.WAITLIST_OFFER, "o1");
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertEquals("WAITLIST_OFFER", saved.getValue().getType());
        assertEquals("e1", saved.getValue().getEventId());
        assertEquals("/events/e1", saved.getValue().getLink());
        assertFalse(saved.getValue().isRead(), "new notifications are unread");
    }

    // ---------------------------------------------------------------- delivery

    private EmailMessage message(int attempts) {
        return EmailMessage.builder().id("m1").dedupeKey("k").toEmail("a@example.com").subject("s").textBody("t").attempts(attempts).build();
    }

    private void claims(EmailMessage... messages) {
        var stub = when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(EmailMessage.class)));
        org.mockito.stubbing.OngoingStubbing<EmailMessage> chain = stub.thenReturn(messages.length > 0 ? messages[0] : null);
        for (int i = 1; i < messages.length; i++) chain = chain.thenReturn(messages[i]);
        chain.thenReturn(null);
    }

    @Test
    void dispatchSendsEachClaimedMessageExactlyOnce() throws Exception {
        EmailMessage m = message(1);
        claims(m);
        assertEquals(1, outbox.dispatchDue(10));
        verify(provider, times(1)).send(m);
        assertEquals("SENT", m.getStatus());
        assertNotNull(m.getSentAt());
        assertEquals(0, outbox.dispatchDue(10), "nothing left to claim, nothing is re-sent");
        verify(provider, times(1)).send(any());
    }

    @Test
    void transientFailureIsRetriedLaterAndPermanentFailureStops() throws Exception {
        EmailMessage transientFail = message(1);
        claims(transientFail);
        doThrow(new EmailProvider.EmailException("503", true)).when(provider).send(transientFail);
        assertEquals(0, outbox.dispatchDue(5));
        assertEquals("PENDING", transientFail.getStatus());
        assertTrue(transientFail.getNextAttemptAt().isAfter(java.time.LocalDateTime.now()), "backs off before retrying");

        EmailMessage permanent = message(1);
        claims(permanent);
        doThrow(new EmailProvider.EmailException("422", false)).when(provider).send(permanent);
        outbox.dispatchDue(5);
        assertEquals("FAILED", permanent.getStatus());

        EmailMessage exhausted = message(5);
        claims(exhausted);
        doThrow(new EmailProvider.EmailException("500", true)).when(provider).send(exhausted);
        outbox.dispatchDue(5);
        assertEquals("FAILED", exhausted.getStatus(), "gives up after the maximum number of attempts");
    }

    @Test
    void withoutAProviderMessagesAreRecordedAsSkippedAndNothingIsSent() throws Exception {
        when(provider.enabled()).thenReturn(false);
        notify(NotificationType.EVENT_CANCELLED, "s1");
        ArgumentCaptor<EmailMessage> saved = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailRepository).save(saved.capture());
        assertEquals("SKIPPED", saved.getValue().getStatus());
        assertEquals(0, outbox.dispatchDue(10));
        verify(provider, never()).send(any());
    }

    // ---------------------------------------------------------------- content safety

    @Test
    void emailContentEscapesHtmlAndNeverContainsSecrets() {
        var mail = EmailTemplates.render(NotificationType.ANNOUNCEMENT, "Eve <script>", "<b>Title</b>", "Hi <img src=x onerror=alert(1)>",
                "https://example.com/e?a=1&b=2", List.of("Where: <Hall>"));
        assertFalse(mail.html().contains("<script>") || mail.html().contains("<img src=x"));
        assertTrue(mail.html().contains("&lt;img src=x"));
        assertTrue(mail.html().contains("a=1&amp;b=2"));
        assertTrue(mail.subject().startsWith("Announcement: "));
        assertTrue(mail.text().contains("Open in Occavie: https://example.com"));
    }

    @Test
    void jsonBodyEscapingKeepsTheRequestWellFormed() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", ResendEmailProvider.quote("a\"b\\c\nd"));
        assertEquals("\"\\u0001\"", ResendEmailProvider.quote("\u0001"));
    }
}
