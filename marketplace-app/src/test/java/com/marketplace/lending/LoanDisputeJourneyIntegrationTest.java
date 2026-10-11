package com.marketplace.lending;

import test.config.IntegrationContainers;

import com.marketplace.disputes.Dispute;
import com.marketplace.disputes.DisputeRepository;
import com.marketplace.disputes.DisputeService;
import com.marketplace.disputes.DisputeStatus;
import com.marketplace.ledger.LedgerEntry;
import com.marketplace.ledger.LedgerEntryRepository;
import com.marketplace.ledger.LedgerEntryType;
import com.marketplace.ledger.LedgerService;
import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.payments.PaymentIntentStatus;
import com.marketplace.payments.PaymentRepository;
import com.marketplace.payments.PaymentStatus;
import com.marketplace.payments.PaymentsService;
import com.marketplace.shared.api.DisputeResolution;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ADR-0009 — the loan's dispute cycle end-to-end over the REAL schema and
 * the REAL modules: the dispute opens through the disputes module's
 * contract pair (the real {@code LoanPartyProviderAdapter}), the shared
 * {@code DisputeOpenedEvent} drives the REAL freeze listener into
 * DISPUTED, and the resolution lands through the machine's own edges — a
 * REFUND_CONSUMER decision terminates the loan whose
 * {@code LoanCancelledEvent} drives the payments module's listener (the
 * ONE refund contract: the collected fee refunded in full, the ledger's
 * refund debit mirroring the loan-origin credit), a NO_ACTION decision
 * releases the freeze back to ACTIVE.
 *
 * <p>Schema honesty: the {@code DisputeFinancialResolutionIntegrationTest}
 * pattern verbatim — Flyway enabled, {@code ddl-auto=none}, a dedicated
 * container, so V174 + V177 + V179 are the schema the cycle runs on. The
 * FK parents (users, store_categories, products) are seeded with raw SQL +
 * ON CONFLICT DO NOTHING; the caller identity is the standard
 * {@code @MockitoBean} boundary while every party gate, freeze, machine
 * edge, and money path in between is production code.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LoanDisputeJourneyIntegrationTest {

    private static final long FEE_MINOR = 1500L;
    private static final long COMMISSION_MINOR = 150L; // rate 0.10 (test profile)

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches DisputeFinancialResolutionIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private DisputeService disputeService;

    @Autowired
    private DisputeRepository disputeRepository;

    @Autowired
    private LendingOfferRepository offerRepository;

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private PaymentsService paymentsService;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private void seedFkParents(UUID ownerId, UUID borrowerId, UUID productId) {
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, ownerId, "adr9-owner-" + ownerId + "@example.com",
                "adr9-owner-" + ownerId + "@example.com", "ADR9 Owner");
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, borrowerId, "adr9-borrower-" + borrowerId + "@example.com",
                "adr9-borrower-" + borrowerId + "@example.com", "ADR9 Borrower");
        jdbc.update("""
                INSERT INTO store_categories (id, code, name_en, name_ar)
                VALUES (?, 'adr9-cat', 'ADR9 category', 'فئة الإعارة')
                ON CONFLICT (code) DO NOTHING
                """, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO products (id, store_category_code, title, description, price_minor, currency, provider_id)
                VALUES (?, 'adr9-cat', 'ADR9 lending item', 'the disputed loan ride', 100000, 'SAR', ?)
                ON CONFLICT (id) DO NOTHING
                """, productId, ownerId);
    }

    /** The paid, handed-over ACTIVE loan planted through the real factories and the real engine. */
    private PaidLoan plantPaidActiveLoan(UUID ownerId, UUID borrowerId, UUID productId) {
        offerRepository.save(LendingOffer.publish(productId, ownerId, 500L, "SAR", 0L, 250L));
        Loan loan = loanRepository.save(Loan.request(productId, ownerId, borrowerId,
                java.time.Instant.parse("2026-11-01T10:00:00Z"),
                java.time.Instant.parse("2026-11-04T10:00:00Z"),
                FEE_MINOR, "SAR", 250L));
        // The fee's intent through the REAL engine (the LOAN origin), then
        // the REAL confirm path — whose COMPLETED event marks the loan paid
        // through the real LendingPaymentListener and credits the owner
        // through the real ledger listener.
        PaymentIntent intent = paymentsService.createLoanIntent(loan.getId(), borrowerId, FEE_MINOR, "SAR");
        paymentsService.confirmIntent(intent.getId(), "evt_adr9_" + intent.getId());
        loan.markPaid(java.time.Instant.now());
        loan.activate(java.time.Instant.now());
        loanRepository.save(loan);
        return new PaidLoan(loan, intent);
    }

    private record PaidLoan(Loan loan, PaymentIntent intent) {
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theRefundResolutionTerminatesTheLoanAndSettlesThroughTheOneRefundContract() {
        UUID ownerId = UUID.randomUUID();
        UUID borrowerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        seedFkParents(ownerId, borrowerId, productId);
        PaidLoan planted = plantPaidActiveLoan(ownerId, borrowerId, productId);
        Loan loan = planted.loan();
        when(currentUserProvider.getCurrentUserId(SecurityContextHolder.getContext().getAuthentication()))
                .thenReturn(borrowerId);

        // The owner's fee settled through the real engine: the credit lands
        // at fee - commission (the loan-origin ledger leg, the real
        // LoanOwnerPort answering who to credit).
        awaitBalance(ownerId, FEE_MINOR - COMMISSION_MINOR);

        // The borrower opens the dispute — the REAL LoanPartyProvider gates
        // it; the shared event's LOAN subject drives the REAL freeze.
        Dispute dispute = disputeService.openForLoan(loan.getId(), "the item came back damaged",
                SecurityContextHolder.getContext().getAuthentication());
        assertThat(disputeRepository.findById(dispute.getId()).orElseThrow().getSubjectType().name())
                .isEqualTo("LOAN");
        awaitLoanStatus(loan.getId(), LoanStatus.DISPUTED);
        assertThat(loanRepository.findById(loan.getId()).orElseThrow().isLive())
                .as("the DISPUTED loan stays in the live set — the period stays held")
                .isTrue();

        // The admin's refund resolution: the loan TERMINATES through the
        // cancellation edge; the LoanCancelledEvent drives the payments
        // module's own listener — the ONE refund contract.
        Dispute resolved = disputeService.resolve(dispute.getId(), DisputeResolution.REFUND_CONSUMER,
                SecurityContextHolder.getContext().getAuthentication());
        assertThat(resolved.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.getRefundPaymentId())
                .as("the loan subject records no booking refund linkage")
                .isNull();
        awaitLoanStatus(loan.getId(), LoanStatus.CANCELLED);
        assertThat(loanRepository.findById(loan.getId()).orElseThrow().getCancelReason())
                .contains("dispute");

        // The money moved exactly once — the collected fee refunded in full.
        awaitPaymentStatus(planted.intent().getId(), PaymentIntentStatus.REFUNDED);
        assertThat(ledgerEntryRepository.findBySourceId(
                        UUID.nameUUIDFromBytes(("refund-" + planted.intent().getId()).getBytes()))
                .map(LedgerEntry::getEntryType))
                .as("the ledger's refund debit mirrors the loan-origin credit")
                .contains(LedgerEntryType.REFUND_DEBIT);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theNoActionResolutionReleasesTheFreezeBackToActive() {
        UUID ownerId = UUID.randomUUID();
        UUID borrowerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        seedFkParents(ownerId, borrowerId, productId);
        PaidLoan planted = plantPaidActiveLoan(ownerId, borrowerId, productId);
        Loan loan = planted.loan();
        when(currentUserProvider.getCurrentUserId(SecurityContextHolder.getContext().getAuthentication()))
                .thenReturn(ownerId);

        Dispute dispute = disputeService.openForLoan(loan.getId(), "a disagreement about the handover",
                SecurityContextHolder.getContext().getAuthentication());
        awaitLoanStatus(loan.getId(), LoanStatus.DISPUTED);

        Dispute resolved = disputeService.resolve(dispute.getId(), DisputeResolution.NO_ACTION,
                SecurityContextHolder.getContext().getAuthentication());
        assertThat(resolved.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        awaitLoanStatus(loan.getId(), LoanStatus.ACTIVE);

        // The freeze released with the money untouched: the intent stays
        // settled and the refund debit never exists.
        awaitIntentNotRefunded(planted.intent().getId());
        assertThat(ledgerEntryRepository.findBySourceId(
                        UUID.nameUUIDFromBytes(("refund-" + planted.intent().getId()).getBytes())))
                .isEmpty();
    }

    /**
     * Plain poll loop (30s / 200ms) — the DisputeFinancialResolution pattern
     * verbatim: the module listeners run AFTER_COMMIT in their own units.
     */
    private void awaitLoanStatus(UUID loanId, LoanStatus expected) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        LoanStatus last = null;
        while (System.nanoTime() < deadline) {
            last = loanRepository.findById(loanId).map(Loan::getStatus).orElse(null);
            if (last == expected) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Loan " + loanId + " never reached " + expected
                + " after 30s (last seen: " + last + ") — the dispute listener did not land.");
    }

    private void awaitPaymentStatus(UUID intentId, PaymentIntentStatus expected) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        PaymentIntentStatus last = null;
        while (System.nanoTime() < deadline) {
            last = paymentIntentRepository.findById(intentId).map(PaymentIntent::getStatus).orElse(null);
            if (last == expected) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Intent " + intentId + " never reached " + expected
                + " after 30s (last seen: " + last + ") — the refund chain did not land.");
    }

    /**
     * The release leg's negative wait: give the listener chain a fixed
     * window to (wrongly) move the money, then assert it stayed settled.
     */
    private void awaitIntentNotRefunded(UUID intentId) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        PaymentIntentStatus last = null;
        while (System.nanoTime() < deadline) {
            last = paymentIntentRepository.findById(intentId).map(PaymentIntent::getStatus).orElse(null);
            if (last == PaymentIntentStatus.REFUNDED) {
                throw new AssertionError("Intent " + intentId + " was refunded — the release"
                        + " resolution moved money it must never move.");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void awaitBalance(UUID ownerId, long expectedCents) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        Long last = null;
        while (System.nanoTime() < deadline) {
            last = ledgerService.getBalances(ownerId).stream()
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
                "Owner %s balance never reached %s after 30s (last seen: %s)",
                ownerId, expectedCents, last));
    }
}
