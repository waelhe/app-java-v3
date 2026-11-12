package com.marketplace.ledger;

import test.config.IntegrationContainers;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * A2 round 3 (CodeRabbit, the requested replay proof): the refund that
 * OUTRUNS the settlement is replayed through the REAL event-publication
 * registry after the commission posts — the full-application form of the
 * module-slice A2 proof ({@code LedgerModuleIntegrationTest}) and the unit
 * fail-closed guard ({@code LedgerPaymentEventListenerTest}).
 *
 * <p>The round-2 guard's own contract: a REFUNDED delivery that arrives
 * before the settlement's commission posting refuses to book ANY movement
 * and throws, so the publication stays recoverable (Spring Modulith
 * reference, «Failed publications and resubmission» — a failed listener
 * marks its publication FAILED and the registry re-delivers it). This test
 * proves the WHOLE loop on the real machinery: the FAILED publication is
 * recorded in {@code event_publication}; after the settlement lands, one
 * {@code com.marketplace.shared.cache.EventPublicationResubmission} sweep
 * re-delivers the SAME refund
 * event; the replay reads the now-POSTED commission, books the refund debit
 * AND the commission-reversal credit, and the publication completes into
 * {@code event_publication_archive} with two completion attempts — the
 * recovery the guard exists for, measured end-to-end (the house
 * {@code EventPublicationResubmissionIntegrationTest} witness shape).</p>
 *
 * <p>Schema honesty: the {@code AuditedWritesIntegrationTest} /
 * {@code DisputeFinancialResolutionIntegrationTest} convention — Flyway
 * enabled, {@code ddl-auto=none}, a dedicated container per class. The
 * booking seam is the standard {@code @MockitoBean} boundary (the L21
 * convention) while every money path in between is production code; the
 * intent lookup is the same boundary the module-slice proof uses, stubbed
 * sequentially: REFUNDED first (the outrunning refund), then COMPLETED
 * (the settlement), then REFUNDED again (the registry replay).</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LedgerRefundReplayIntegrationTest {

    private static final long PRICE_CENTS = 5000L;
    private static final long COMMISSION_CENTS = 500L; // rate 0.10 (test profile)

    /** The ledger listener's registry identity — the poll/cleanup scope. */
    private static final String LISTENER_PATTERN = "%LedgerPaymentEventListener%onPaymentCompleted%";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.marketplace.shared.cache.EventPublicationResubmission resubmission;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @AfterEach
    void cleanLedgerListenerRows() {
        // The house convention (EventPublicationResubmissionIntegrationTest):
        // the registry tables are standalone — deleting by the listener's own
        // identity leaves every other publication untouched.
        jdbc.update("DELETE FROM event_publication WHERE listener_id LIKE ?", LISTENER_PATTERN);
        jdbc.update("DELETE FROM event_publication_archive WHERE listener_id LIKE ?", LISTENER_PATTERN);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aRefundThatOutrunsTheSettlementIsReplayedByTheRegistryAfterTheCommissionPosts() {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();

        // The booking seam (the L21 convention): the price and ownership the
        // money loop books against.
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", PRICE_CENTS, "SAR",
                Instant.now(), Instant.now()));
        // The intent lookup's three deliveries: the outrunning refund, the
        // settlement, then the registry's replay of the SAME refund.
        PaymentIntentDetails refundedIntent = new PaymentIntentDetails(paymentIntentId, bookingId,
                consumerId, null, "REFUNDED", "BOOKING", 25000L, "SAR", null, null, null);
        PaymentIntentDetails completedIntent = new PaymentIntentDetails(paymentIntentId, bookingId,
                consumerId, null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null);
        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(refundedIntent), Optional.of(completedIntent),
                        Optional.of(refundedIntent));

        // 1 — the refund outruns the settlement: the guard refuses to book
        //     ANY movement and the publication is recorded FAILED.
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "REFUNDED")));
        awaitPublication("FAILED", 1, "the outrunning refund marks its publication FAILED");

        //     Fail-closed BEFORE any write: no balance, no statement row.
        assertThat(ledgerService.getBalances(providerId)).isEmpty();
        assertThat(ledgerEntryRepository.findByProviderIdOrderByCreatedAtDescIdDesc(
                providerId, PageRequest.of(0, 50))).isEmpty();

        // 2 — the settlement lands: the COMPLETED pair (credit + commission
        //     debit) books through the real listener chain.
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
        awaitBalance(providerId, PRICE_CENTS - COMMISSION_CENTS);

        // 3 — one registry sweep re-delivers the SAME refund: the replay now
        //     reads the POSTED commission, books the refund debit AND the
        //     commission-reversal credit, and the provider returns to the
        //     pre-settlement zero (the ADR-0013 D3 §3 mirror rule, recovered).
        resubmission.resubmitDue(Instant.now());
        awaitBalance(providerId, 0L);

        //     The replay completed through the registry: the archived
        //     publication carries the original attempt PLUS the resubmission.
        awaitArchived(2, "the replayed refund publication completed with two attempts");

        // 4 — the exact ledger, once each: the settlement pair and its mirror
        //     pair — four movements, no duplicated row (the replay's
        //     second delivery can never double-book: the derived source ids
        //     make both mirrors structurally idempotent).
        var statement = ledgerEntryRepository.findByProviderIdOrderByCreatedAtDescIdDesc(
                providerId, PageRequest.of(0, 50));
        assertThat(statement.getContent()).hasSize(4);
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getSourceId, LedgerEntry::getEntryType, LedgerEntry::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(paymentIntentId,
                                LedgerEntryType.PAYMENT_CREDIT, PRICE_CENTS),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_DEBIT, COMMISSION_CENTS),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("refund-" + paymentIntentId).getBytes()),
                                LedgerEntryType.REFUND_DEBIT, PRICE_CENTS),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-reversal-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_REVERSAL_CREDIT, COMMISSION_CENTS));

        //     The final balance: the pre-settlement zero, in one currency.
        assertThat(ledgerService.getBalances(providerId)).hasSize(1);
        assertThat(ledgerService.getBalances(providerId).get(0).availableCents()).isZero();
        assertThat(ledgerService.getBalances(providerId).get(0).currency()).isEqualTo("SAR");
    }

    /**
     * The house poll for a publication state (the
     * {@code EventPublicationResubmissionIntegrationTest} shape).
     */
    private void awaitPublication(String status, int minAttempts, String description) {
        poll(description, () -> {
            Long count = jdbc.queryForObject(
                    "select count(*) from event_publication where listener_id like ?"
                            + " and status = ? and completion_attempts >= ?",
                    Long.class, LISTENER_PATTERN, status, minAttempts);
            return count != null && count > 0;
        });
    }

    /**
     * The house poll for the archived completion (the
     * {@code EventPublicationResubmissionIntegrationTest} shape).
     */
    private void awaitArchived(int minAttempts, String description) {
        poll(description, () -> {
            Long count = jdbc.queryForObject(
                    "select count(*) from event_publication_archive where listener_id like ?"
                            + " and completion_date is not null and completion_attempts >= ?",
                    Long.class, LISTENER_PATTERN, minAttempts);
            return count != null && count > 0;
        });
    }

    /**
     * Plain poll loop (30s / 200ms) — the {@code
     * DisputeFinancialResolutionIntegrationTest} shape: the async payment-state
     * listener lands the ledger movement AFTER_COMMIT.
     */
    private void awaitBalance(UUID providerId, long expectedCents) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        Long last = null;
        while (System.nanoTime() < deadline) {
            last = ledgerService.getBalances(providerId).stream()
                    .filter(b -> "SAR".equals(b.currency()))
                    .mapToLong(com.marketplace.ledger.ProviderBalanceResponse::availableCents)
                    .findFirst().orElse(Long.MIN_VALUE);
            if (last != null && last == expectedCents) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(String.format(
                "Provider %s balance never reached %s after 30s (last seen: %s) —"
                        + " the async payment-state listener did not land the ledger movement.",
                providerId, expectedCents, last));
    }

    private void poll(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Condition not met within 30s: " + description);
    }
}
