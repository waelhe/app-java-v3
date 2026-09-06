package com.marketplace.payments;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression test for the Envers audit tables against the <em>real Flyway
 * schema</em> — the guard that was missing when V26/V27 shipped.
 *
 * <p>Root cause this guards: V26 added {@code bookings.starts_at/ends_at}
 * and V27 added {@code refunded_amount_cents} to the base tables only — the
 * Envers audit tables never received the matching columns. Official Envers
 * behavior (hibernate-envers 7.4.5.Final sources, AddWorkUnit:35-41): the
 * audit INSERT data is built from {@code entityPersister.getPropertyNames()},
 * i.e. every entity property must exist in the {@code _aud} table. A live
 * probe (2026-09-05, this branch) on a real database after V1..V32 proved:
 * an Envers-style INSERT failed with
 * <pre>
 * ERROR: column "refunded_amount_cents" of relation "payment_intents_aud" does not exist
 * </pre>
 * Zero CI coverage caught the gap because no integration test wrote audited
 * payments/booking entities — every payments test mocks the repositories.
 * {@code V33__payments_psp_channel.sql} closes the debt; this test fails on
 * any recurrence by writing the affected entities through the real entity
 * manager and asserting the audit rows carry the new columns.
 *
 * <p>Follows the {@code EventPublicationArchiveIntegrationTest} /
 * {@code CatalogSearchFullTextIntegrationTest} patterns: full application
 * context on real PostgreSQL with Flyway enabled and {@code ddl-auto=none},
 * so Envers runs against exactly the schema migrations produce (the
 * {@code test} profile's {@code ddl-auto: create-drop} is overridden).
 * FK parents are seeded with raw SQL + ON CONFLICT DO NOTHING (the house
 * seeding convention), then the audited entities are written through the
 * real repositories so the full Envers path executes.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AuditedWritesIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches EventPublicationArchiveIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentsService paymentsService;

    /** FK parents for payment_intents: users -> provider_listings -> bookings. */
    private static final UUID CONSUMER_ID = UUID.randomUUID();
    private static final UUID PROVIDER_USER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();

    /**
     * The real IdentityUserProvider resolves users from JWT subjects — this
     * test exercises the audited write path, not the auth resolution, so the
     * provider is replaced at the boundary (house pattern from
     * PaymentsServiceSecurityTest).
     */
    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedFkParents() {
        // Idempotent across test methods (ON CONFLICT DO NOTHING) — the
        // migration schema enforces payment_intents.booking_id -> bookings(id)
        // -> provider_listings(id) -> users(id).
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, CONSUMER_ID, "audited-consumer@example.com",
                "audited-consumer@example.com", "Audited Writes Consumer");
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, PROVIDER_USER_ID, "audited-provider@example.com",
                "audited-provider@example.com", "Audited Writes Provider");
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category,
                    price_cents, currency, status)
                VALUES (?, ?, 'audited test listing', 'x', 'CLEANING', 5000, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, LISTING_ID, PROVIDER_USER_ID);
        jdbc.update("""
                INSERT INTO bookings (id, listing_id, consumer_id, provider_id, status, price_cents, currency)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 5000, 'SAR')
                ON CONFLICT (id) DO NOTHING
                """, BOOKING_ID, LISTING_ID, CONSUMER_ID, PROVIDER_USER_ID);
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(CONSUMER_ID);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
    }

    @Test
    void auditedPaymentWritesSurviveOnTheFlywaySchema() {
        UUID intentId = transactionTemplate.execute(status -> {
            PaymentIntent intent = PaymentIntent.create(BOOKING_ID, CONSUMER_ID, 5000L,
                    "audit-" + UUID.randomUUID());
            intent = paymentIntentRepository.save(intent);
            Payment payment = Payment.create(intent.getId(), intent.getAmountCents());
            paymentRepository.save(payment);
            return intent.getId();
        });

        // The audit row must carry the V27 column that V33 restores.
        Integer paymentAudWithRefunded = jdbc.queryForObject(
                "SELECT count(*) FROM payment_intents_aud WHERE id = ? AND refunded_amount_cents IS NOT NULL",
                Integer.class, intentId);
        assertThat(paymentAudWithRefunded)
                .as("payment_intents_aud must carry refunded_amount_cents (V27 column, V33 audit fix)")
                .isEqualTo(1);

        Integer paymentsAudWithRefunded = jdbc.queryForObject(
                "SELECT count(*) FROM payments_aud WHERE payment_intent_id = ? AND refunded_amount_cents IS NOT NULL",
                Integer.class, intentId);
        assertThat(paymentsAudWithRefunded)
                .as("payments_aud must carry refunded_amount_cents")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void auditedPaymentWriteCarriesThePspLinkColumn() {
        UUID intentId = transactionTemplate.execute(status -> {
            PaymentIntent intent = PaymentIntent.create(BOOKING_ID, CONSUMER_ID, 1000L, null);
            return paymentIntentRepository.save(intent).getId();
        });

        Integer audWithPspColumn = jdbc.queryForObject(
                "SELECT count(*) FROM payment_intents_aud WHERE id = ? AND psp_intent_id IS NULL",
                Integer.class, intentId);
        assertThat(audWithPspColumn)
                .as("payment_intents_aud must carry psp_intent_id (V33) — the column the audit INSERT writes")
                .isEqualTo(1);
    }

    @Test
    void processIntentOnInertChannelRunsTheFullAuditedWrite() {
        Authentication consumer = new TestingAuthenticationToken(
                "audited-consumer@example.com", "n/a", "ROLE_CONSUMER");
        SecurityContextHolder.getContext().setAuthentication(consumer);
        try {
            UUID intentId = transactionTemplate.execute(status -> {
                PaymentIntent intent = PaymentIntent.create(BOOKING_ID, CONSUMER_ID, 2500L, null);
                return paymentIntentRepository.save(intent).getId();
            });

            PaymentsService.ProcessIntentResult result = transactionTemplate.execute(status ->
                    paymentsService.processIntent(intentId, consumer));

            assertThat(result).isNotNull();
            assertThat(result.clientSecret())
                    .as("no PAYMENTS_STRIPE_* credentials in CI — the channel stays inert")
                    .isNull();

            Integer auditRows = jdbc.queryForObject(
                    "SELECT count(*) FROM payment_intents_aud WHERE id = ?",
                    Integer.class, intentId);
            assertThat(auditRows).as("the PROCESSING transition writes an audit revision").isGreaterThanOrEqualTo(2);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void auditedBookingColumnsAreQueryable() {
        // V26 added starts_at/ends_at to bookings; V33 restores the audit
        // columns. The audit table must accept the full entity write shape —
        // proven indirectly by booking flows elsewhere; here we pin the
        // schema shape itself.
        Integer auditColumnCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'bookings_aud'"
                        + " AND column_name IN ('starts_at', 'ends_at')",
                Integer.class);
        assertThat(auditColumnCount)
                .as("bookings_aud must carry both V26 columns (V33 audit fix)")
                .isEqualTo(2);
    }
}
