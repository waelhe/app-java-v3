package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.email.EmailService;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L22 (feature-expansion roadmap §5, Week 2) — the notification preference
 * loop over the REAL schema and the REAL notification modules.
 *
 * <p>Acceptance criteria (§5-L22): (1) a user who unsubscribed from EMAIL
 * for PAYMENT_STATE still gets the in-app notification and the WS push,
 * with NO email call for them — verified against the real preference row
 * in the database; (2) the default (no preference rows) is exactly the
 * pre-L22 behavior — both emails, both pushes, both in-app rows; (3) every
 * switch change leaves an Envers trace on the @Audited preference row
 * (V40: notification_preferences + notification_preferences_aud).
 *
 * <p>Schema honesty: the {@code AuditedWritesIntegrationTest} /
 * {@code DisputeFinancialResolutionIntegrationTest} pattern — Flyway
 * enabled and {@code ddl-auto=none} against a dedicated container, so V40
 * is the schema the loop runs on, not an entity-generated one. The users
 * are seeded with raw SQL + ON CONFLICT DO NOTHING (the house seeding
 * convention) because the real {@code UserLookupPort} resolves the email
 * the EmailNotificationService delivers to. The booking/payment seams are
 * the standard {@code @MockitoBean} boundary (the L21/L24 convention) so
 * the recipients are controlled while every delivery path in between is
 * production code. {@code EmailService} and {@code SimpMessagingTemplate}
 * are mocked to OBSERVE the deliveries (in the test profile no mail sender
 * exists, so the email channel would otherwise be a silent no-op).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NotificationPreferencesIntegrationTest {

    private static final long PRICE_CENTS = 5000L;

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches AuditedWritesIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    /** Delivery observation only — the gating logic under test is production code. */
    @MockitoBean
    EmailService emailService;

    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationPreferenceService preferenceService;

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Seeds a users row — the real UserLookupPort resolves the email the
     * EmailNotificationService delivers to (the AuditedWrites convention:
     * raw SQL + ON CONFLICT DO NOTHING, idempotent per test).
     */
    private UUID seedUser(UUID id, String email, String role) {
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id, email, email, "L22 " + role, role);
        return id;
    }

    private void firePaymentState(UUID consumerId, UUID providerId, UUID intentId, UUID bookingId) {
        when(paymentIntentLookupPort.findById(intentId)).thenReturn(Optional.of(
                new PaymentIntentDetails(intentId, bookingId, consumerId, "COMPLETED")));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", PRICE_CENTS, "SAR", Instant.now(), Instant.now()));
        notificationService.onPaymentStateChanged(intentId, "COMPLETED");
    }

    @Test
    @WithMockUser
    void unsubscribedPaymentEmailStillCreatesInAppAndWebSocket() {
        // Acceptance 1 — the full stack: the consumer's stored override (a
        // real V40 row written through the real service) suppresses THEIR
        // email only; the in-app row and the WS push still happen, and the
        // provider's email still goes.
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        String consumerEmail = "l22-consumer-" + consumerId + "@example.com";
        String providerEmail = "l22-provider-" + providerId + "@example.com";
        seedUser(consumerId, consumerEmail, "CONSUMER");
        seedUser(providerId, providerEmail, "PROVIDER");
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerId);

        preferenceService.updateMyPreferences(
                SecurityContextHolder.getContext().getAuthentication(),
                new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false))));

        UUID intentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        firePaymentState(consumerId, providerId, intentId, bookingId);

        // The always-on in-app channel: both rows landed.
        assertThat(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(consumerId))
                .hasSize(1);
        assertThat(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(consumerId)
                .getFirst().getType()).isEqualTo(NotificationType.PAYMENT_STATE.name());
        assertThat(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(providerId))
                .hasSize(1);

        // WS still pushed to both.
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(WebSocketNotification.class));

        // Email: the provider's goes, the consumer's is suppressed.
        verify(emailService, times(1)).send(eq(providerEmail), anyString(), anyString(), anyMap());
        verify(emailService, never()).send(eq(consumerEmail), anyString(), anyString(), anyMap());

        // Acceptance 3 — the Envers trace: the switch change left its revision.
        NotificationPreference stored = preferenceRepository
                .findByUserIdAndTypeAndChannel(consumerId, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL)
                .orElseThrow();
        assertThat(stored.isEnabled()).isFalse();
        var revisions = preferenceRepository.findRevisions(stored.getId(), Pageable.unpaged());
        assertThat(revisions.getContent()).hasSize(1);
        assertThat(revisions.getContent().getFirst().getEntity().isEnabled()).isFalse();
    }

    @Test
    @WithMockUser
    void defaultPreferencesBehaveExactlyAsBefore() {
        // Acceptance 2 — no preference rows at all: both emails, both WS
        // pushes, both in-app rows — byte-for-byte the pre-L22 behavior.
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        String consumerEmail = "l22-default-consumer-" + consumerId + "@example.com";
        String providerEmail = "l22-default-provider-" + providerId + "@example.com";
        seedUser(consumerId, consumerEmail, "CONSUMER");
        seedUser(providerId, providerEmail, "PROVIDER");

        UUID intentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        firePaymentState(consumerId, providerId, intentId, bookingId);

        assertThat(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(consumerId)).hasSize(1);
        assertThat(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(providerId)).hasSize(1);
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(WebSocketNotification.class));
        verify(emailService, times(1)).send(eq(consumerEmail), anyString(), anyString(), anyMap());
        verify(emailService, times(1)).send(eq(providerEmail), anyString(), anyString(), anyMap());

        // The effective matrix reads all-enabled for a user with no overrides.
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerId);
        List<NotificationPreferenceView> matrix = preferenceService.getMyPreferences(
                SecurityContextHolder.getContext().getAuthentication());
        assertThat(matrix).hasSize(6);
        assertThat(matrix).allMatch(NotificationPreferenceView::enabled);
    }

    @Test
    @WithMockUser
    void preferenceFlipLeavesAnAuditRevisionTrail() {
        // Acceptance 3 — every switch change is audited: disable (ADD
        // revision), re-enable (MOD revision); the snapshots carry the
        // historical states, the row carries the current one.
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        preferenceService.updateMyPreferences(authentication,
                new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false))));
        preferenceService.updateMyPreferences(authentication,
                new NotificationPreferencesUpdateRequest(List.of(
                        new NotificationPreferenceUpdate(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, true))));

        NotificationPreference stored = preferenceRepository
                .findByUserIdAndTypeAndChannel(userId, NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL)
                .orElseThrow();
        assertThat(stored.isEnabled()).isTrue();
        var revisions = preferenceRepository.findRevisions(stored.getId(), Pageable.unpaged());
        assertThat(revisions.getContent()).hasSize(2);
        assertThat(revisions.getContent().getFirst().getEntity().isEnabled()).isFalse();
        assertThat(revisions.getContent().get(1).getEntity().isEnabled()).isTrue();
    }
}
