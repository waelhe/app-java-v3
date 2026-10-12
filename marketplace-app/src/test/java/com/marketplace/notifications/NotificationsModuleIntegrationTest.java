package com.marketplace.notifications;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class NotificationsModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @MockitoBean
    UserLookupPort userLookupPort;

    // Phase 7 (the single routing engine + the geo preferences + the urgent
    // router): the module's new services lean on four more cross-module
    // ports whose adapters live OUTSIDE this slice — the geo lookup
    // (marketplace-geo), the community membership and neighborhood members
    // (marketplace-community), and the urgent alerts (marketplace-
    // institutions). The house @MockitoBean pattern for outside-slice ports
    // (the ReviewsModuleIntegrationTest/PricingModuleIntegrationTest
    // convention, the GroupLookupPort round of 28c75d49 the same shape):
    // without the mocks the context boot fails on the constructors.
    @MockitoBean
    com.marketplace.shared.api.GeoLookupPort geoLookupPort;

    @MockitoBean
    com.marketplace.shared.api.CommunityMembershipPort communityMembershipPort;

    @MockitoBean
    com.marketplace.shared.api.NeighborhoodMembersPort neighborhoodMembersPort;

    @MockitoBean
    com.marketplace.shared.api.UrgentAlertsPort urgentAlertsPort;

    /**
     * Phase 7: the routing engine's injectable clock — the production bean
     * lives in platform-infra's ClockConfig, which this slice does not scan
     * (the catalog slice's exact pattern, CatalogModuleIntegrationTest's
     * nested ClockBean — one Clock per context, never two; the CI-measured
     * round: NotificationRoutingEngine's constructor parameter 5 stops the
     * context boot without it).
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class ClockBean {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoads() {
    }

    @Test
    void notificationEventListener_isBean() {
        assertThat(notificationService).isNotNull();
    }

    // ---- Plan item 2.6 guards: the paginated feed + the unread badge, on the
    // REAL migration schema (the module slice boots Flyway with ddl-auto=none) ----

    @Test
    void getMyNotifications_isAPagedReadOfOnlyTheCallersRows() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        notificationRepository.save(Notification.create(me,
                NotificationType.PAYMENT_STATE.name(), "mine-1"));
        notificationRepository.save(Notification.create(me,
                NotificationType.PAYMENT_STATE.name(), "mine-2"));
        notificationRepository.save(Notification.create(other,
                NotificationType.PAYMENT_STATE.name(), "not-mine"));
        notificationRepository.flush();

        when(currentUserProvider.getCurrentUserId(org.mockito.ArgumentMatchers.any(Authentication.class)))
                .thenReturn(me);
        Authentication auth = new TestingAuthenticationToken("u", "p");

        var page = notificationService.getMyNotifications(auth, PageRequest.of(0, 1));

        assertThat(page.getTotalElements()).isEqualTo(2);   // the other user's rows never leak
        assertThat(page.getNumberOfElements()).isEqualTo(1); // the page honors its size
        assertThat(page.getContent()).allMatch(n -> n.recipientId().equals(me));
    }

    @Test
    void getUnreadCount_countsOnlyUnreadAndMovesWhenMarkedRead() {
        UUID me = UUID.randomUUID();
        Notification unread = notificationRepository.save(Notification.create(me,
                NotificationType.PAYMENT_STATE.name(), "unread"));
        notificationRepository.save(Notification.create(me,
                NotificationType.PAYMENT_STATE.name(), "also-unread"));
        notificationRepository.flush();

        when(currentUserProvider.getCurrentUserId(org.mockito.ArgumentMatchers.any(Authentication.class)))
                .thenReturn(me);
        Authentication auth = new TestingAuthenticationToken("u", "p");

        assertThat(notificationService.getUnreadCount(auth)).isEqualTo(2);

        unread.markRead();
        notificationRepository.save(unread);
        notificationRepository.flush();

        assertThat(notificationService.getUnreadCount(auth)).isEqualTo(1);
    }

    /**
     * CodeRabbit review on #427 (adopted): the mark-read response carries the
     * PERSISTED metadata — version and updatedAt are read after the explicit
     * flush, and the row's read flag is durable before the client sees 200.
     */
    @Test
    void markAsRead_returnsPersistedMetadataForUnread() {
        UUID me = UUID.randomUUID();
        Notification unread = notificationRepository.save(Notification.create(me,
                NotificationType.PAYMENT_STATE.name(), "unread"));
        notificationRepository.flush();

        when(currentUserProvider.getCurrentUserId(org.mockito.ArgumentMatchers.any(Authentication.class)))
                .thenReturn(me);
        Authentication auth = new TestingAuthenticationToken("u", "p");

        NotificationResponse response = notificationService.markAsRead(unread.getId(), auth);

        assertThat(response.read()).isTrue();
        assertThat(response.version()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
        assertThat(notificationRepository.findById(unread.getId()).orElseThrow().isRead()).isTrue();
    }

    /**
     * The #545 measured defect, closed at its own seam: the urgent-alert
     * inbox's native idempotent insert EXECUTED against the real migration
     * schema (V18 + V25 + V180) — the pre-fix statement omitted
     * created_at/updated_at (NOT NULL, NO database default) so EVERY inbox
     * insert answered 23502 and the Modulith registry retried the fan-out
     * forever, a defect invisible to every mock-based router test. The
     * audit columns now ride the statement as bound parameters; the
     * V180 ledger key's duplicate answers a returned 0 — the V54/V199
     * discipline, never a transaction-aborting 23505.
     */
    @Test
    void insertOnce_stampsTheAuditColumnsAndAbsorbsTheDuplicate() {
        UUID recipient = UUID.randomUUID();
        UUID sourceEvent = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-11T08:00:00Z");
        Instant updatedAt = Instant.parse("2026-10-11T08:00:05Z");

        int first = notificationRepository.insertOnce(UUID.randomUUID(), recipient,
                NotificationType.URGENT_ALERT.name(), "المصدر الرسمي: إغلاق طريق رئيسي",
                sourceEvent, createdAt, updatedAt);
        assertThat(first).as("the first (recipient, source event) insert wins").isEqualTo(1);

        int duplicate = notificationRepository.insertOnce(UUID.randomUUID(), recipient,
                NotificationType.URGENT_ALERT.name(), "المصدر الرسمي: إغلاق طريق رئيسي",
                sourceEvent, createdAt.plusSeconds(60), updatedAt.plusSeconds(60));
        assertThat(duplicate).as("the ledger key absorbs the re-delivery as a returned 0")
                .isZero();

        assertThat(notificationRepository.existsByRecipientIdAndSourceEventId(recipient,
                sourceEvent)).as("exactly ONE row survived the duplicate").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ?",
                Integer.class, recipient)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? "
                        + "AND source_event_id IS NULL",
                Integer.class, recipient)).as("the legacy NULL-birth paths stay outside "
                        + "the ledger's constraint").isZero();

        java.sql.Timestamp createdStamp = jdbc.queryForObject(
                "SELECT created_at FROM notifications WHERE recipient_id = ?",
                java.sql.Timestamp.class, recipient);
        java.sql.Timestamp updatedStamp = jdbc.queryForObject(
                "SELECT updated_at FROM notifications WHERE recipient_id = ?",
                java.sql.Timestamp.class, recipient);
        assertThat(createdStamp.toInstant()).isEqualTo(createdAt);
        assertThat(updatedStamp.toInstant()).isEqualTo(updatedAt);

        // The 23502 closure's complete form: is_deleted and version are
        // BOUND too — their NOT NULL shape comes from the entity mapping
        // (BaseEntity's @SoftDelete and @Version), and the database
        // default exists only on the Flyway chain (V25), not on the
        // Hibernate-generated schema this slice runs against. The pre-fix
        // statement relied on V25's default and failed HERE with
        // "null value in column is_deleted violates not-null constraint"
        // — the measured CI failure this assertion pins shut.
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM notifications WHERE recipient_id = ?",
                Boolean.class, recipient)).as("the soft-delete column is born FALSE")
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM notifications WHERE recipient_id = ?",
                Long.class, recipient)).as("the optimistic-lock column is born 0")
                .isZero();
    }
}
