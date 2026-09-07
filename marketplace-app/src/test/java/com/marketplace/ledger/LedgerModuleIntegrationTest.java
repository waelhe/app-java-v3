package com.marketplace.ledger;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class LedgerModuleIntegrationTest {

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    /** AuthHelper (shared-security, now a ledger dependency) collaborators. */
    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ProviderLookupPort providerLookupPort;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    /**
     * The ledger module context boots with its declared dependencies
     * (Modulith boundary smoke test).
     */
    @Test
    void contextLoads() {
    }

    /**
     * A payment credit creates the provider balance and the PAYMENT_CREDIT
     * entry inside the ledger module boundary.
     */
    @Test
    void creditFromPayment_createsBalance() {
        var balance = ledgerService.creditFromPayment(UUID.randomUUID(), UUID.randomUUID(), 1000L);
        assertThat(balance).isNotNull();
        assertThat(balance.getAvailableCents()).isEqualTo(1000L);
    }

    /**
     * L20 acceptance criterion 2 (roadmap §5): a COMPLETED payment event
     * credits the ledger, and the provider sees the movement through the new
     * provider-facing access (balance + statement). The event is published
     * inside a committed transaction because {@code @ApplicationModuleListener}
     * dispatch is AFTER_COMMIT + async (same contract as
     * {@code EventPublicationArchiveIntegrationTest}); the plain poll loop
     * waits for the listener's own transaction to land the balance.
     */
    /**
     * A payment-completed event credits the provider ledger and the owner
     * sees the movement through provider-scoped access — the provider
     stub resolves by user id (A1).
     */
    @Test
    void paymentCompletedEvent_creditsLedger_andOwnerSeesMovementThroughProviderAccess() {
        UUID providerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        long priceCents = 5000L;
        long commissionCents = 500L; // app.commission.rate = 0.10 (test profile)

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(
                new PaymentIntentDetails(paymentIntentId, bookingId, consumerId, "COMPLETED")));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", priceCents, "SAR",
                Instant.now(), Instant.now()));
        // The guarded reads must be authorized even if method security is
        // enforced in a future slice setup: stub the AuthHelper collaborators
        // so the owner mapping is consistent (CodeRabbit #248 round 1).
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "Test Provider", "VERIFIED", ownerUserId)));

        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));

    /**
     * Polls the async balance projection until the predicate holds
     * (AFTER_COMMIT event-listener delivery).
     */
        awaitBalance(providerId, priceCents - commissionCents);

        ProviderBalance balance = ledgerService.getBalanceForOwner(providerId);
        assertThat(balance.getAvailableCents()).isEqualTo(priceCents - commissionCents);

        Page<LedgerEntry> statement = ledgerService.getStatementForOwner(providerId, PageRequest.of(0, 10));
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getSourceId, LedgerEntry::getEntryType, LedgerEntry::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(paymentIntentId, LedgerEntryType.PAYMENT_CREDIT, priceCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_DEBIT, commissionCents));

        // Equal-timestamp stability across pages (CodeRabbit #248 round 1): the
        // credit + commission pair lands in ONE listener transaction, so both
        // rows share createdAt — paging at size 1 walks the tie deterministically
        // (createdAt DESC, id DESC): the two pages are disjoint and together hold
        // exactly the two movements.
        Page<LedgerEntry> pageOne = ledgerService.getStatementForOwner(providerId, PageRequest.of(0, 1));
        Page<LedgerEntry> pageTwo = ledgerService.getStatementForOwner(providerId, PageRequest.of(1, 1));
        assertThat(pageOne.getContent()).hasSize(1);
        assertThat(pageTwo.getContent()).hasSize(1);
        assertThat(pageOne.getContent().get(0).getId())
                .isNotEqualTo(pageTwo.getContent().get(0).getId());
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getId)
                .containsExactlyInAnyOrder(
                        pageOne.getContent().get(0).getId(), pageTwo.getContent().get(0).getId());
    }

    /** Plain poll loop (30s / 200ms) — no Awaitility dependency in this reactor. */
    private void awaitBalance(UUID providerId, long expectedCents) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        long last = Long.MIN_VALUE;
        while (System.nanoTime() < deadline) {
            last = ledgerService.getBalance(providerId).getAvailableCents();
            if (last == expectedCents) {
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
                "Ledger balance for provider %s never reached %d cents after 30s (last seen: %d) —"
                        + " the async PaymentStateChangedEvent listener did not land the credit.",
                providerId, expectedCents, last));
    }
}
