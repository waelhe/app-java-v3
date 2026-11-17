package com.marketplace.ledger;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.OrderSellerPort;
import com.marketplace.shared.api.LoanOwnerPort;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    /** AuthHelper (shared-security, now a ledger dependency) collaborators. */
    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ProviderLookupPort providerLookupPort;

    /**
     * The seller port is implemented in marketplace-orders — outside this
     * module slice — so the slice replaces it at the boundary (the same
     * @MockitoBean treatment ProviderLookupPort gets; the settlement-wave
     * contract added the port to LedgerPaymentEventListener's constructor
     * — the CI-measured 2026-10-12 first run of this slice).
     */
    @MockitoBean
    OrderSellerPort orderSellerPort;

    /**
     * The loan owner port is implemented in marketplace-lending — outside
     * this module slice — so the slice replaces it at the boundary (the
     * same @MockitoBean treatment OrderSellerPort gets; the settlement
     * contract's loan leg reaches this listener through the same port
     * family — the CI-measured 2026-10-12 second run: param 4).
     */
    @MockitoBean
    LoanOwnerPort loanOwnerPort;

    @Autowired
    private LedgerService ledgerService;

    /**
     * The repository the stats net window reads through — the round-6
     * sign-fix proof's own access path (sumNetCentsByCurrencyForProviderBetween).
     */
    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    /**
     * A3's guarded critical section lives in this module — the delivery
     * journal's refund-delta read-modify-write. The race proof runs the REAL
     * service on the REAL database (the two-thread webhook-snapshot replay
     * the documented gate recommendation prescribes).
     */
    @Autowired
    private LedgerJournalService journalService;

    /** The A3 race proof's read side — the SUM assertion over the journal's
     * own table (the same DataSource the service's advisory lock binds). */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
        var balance = ledgerService.creditFromPayment(UUID.randomUUID(), UUID.randomUUID(), 1000L, "SAR");
        assertThat(balance).isNotNull();
        assertThat(balance.availableCents()).isEqualTo(1000L);
        assertThat(balance.currency()).isEqualTo("SAR");
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
                new PaymentIntentDetails(paymentIntentId, bookingId, consumerId, null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null)));
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

        var balances = ledgerService.getBalancesForOwner(providerId);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).availableCents()).isEqualTo(priceCents - commissionCents);
        assertThat(balances.get(0).currency()).isEqualTo("SAR");

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

    /**
     * A2 (adversarial review 2026-11-10, owner-gate batch 6a — the
     * ADR-0013 D3 §3 mirror rule): a full refund reverses the credit AND
     * its commission mirror — the module-slice proof on the real
     * @ApplicationModuleListener dispatch, real PostgreSQL, the whole
     * four-entry statement. The COMPLETED settlement lands the pair
     * (PAYMENT_CREDIT + COMMISSION_DEBIT), the REFUNDED reversal lands
     * its own pair (REFUND_DEBIT + COMMISSION_REVERSAL_CREDIT), and the
     * provider's balance returns to its pre-settlement ZERO — the
     * ADR's own words: «a full REFUND reverses the credit and its
     * commission mirror» — where the pre-fix code left it at minus the
     * commission (the ledger's own finding A2).
     */
    @Test
    void refundedEvent_reversesBothTheCreditAndItsCommissionMirror() {
        UUID providerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        long priceCents = 5000L;
        long commissionCents = 500L; // app.commission.rate = 0.10 (test profile)

        PaymentIntentDetails completed = new PaymentIntentDetails(paymentIntentId, bookingId,
                consumerId, null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null);
        PaymentIntentDetails refunded = new PaymentIntentDetails(paymentIntentId, bookingId,
                consumerId, null, "REFUNDED", "BOOKING", 25000L, "SAR", null, null, null);
        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(completed), Optional.of(refunded));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", priceCents, "SAR",
                Instant.now(), Instant.now()));
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "A2 Provider", "VERIFIED", ownerUserId)));

        // The settlement pair.
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
        awaitBalance(providerId, priceCents - commissionCents);

        // The full-refund reversal pair — the balance returns to zero.
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "REFUNDED")));
        awaitBalance(providerId, 0L);

        var balances = ledgerService.getBalancesForOwner(providerId);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).availableCents())
                .as("A2: the refunded balance returns to its pre-settlement zero, "
                        + "not to a commission-sized negative")
                .isZero();
        assertThat(balances.get(0).currency()).isEqualTo("SAR");

        // The whole statement: the settlement pair and its mirror pair —
        // four movements, one closed-set member each.
        Page<LedgerEntry> statement = ledgerService.getStatementForOwner(providerId, PageRequest.of(0, 10));
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getSourceId, LedgerEntry::getEntryType, LedgerEntry::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(paymentIntentId, LedgerEntryType.PAYMENT_CREDIT, priceCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_DEBIT, commissionCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("refund-" + paymentIntentId).getBytes()),
                                LedgerEntryType.REFUND_DEBIT, priceCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-reversal-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_REVERSAL_CREDIT, commissionCents));
    }

    /**
     * A2 + CodeRabbit round 3 (major — data integrity), rate 0: a
     * settlement that froze a ZERO commission posts its PAYMENT_CREDIT but
     * NO COMMISSION_DEBIT row (the zero-amount rule) — the refund then
     * debits the credit alone, with NO reversal row, and the balance
     * returns to its pre-settlement zero through the credit's full amount.
     * The pre-fix guard deadlocked this refund forever: it read "no
     * commission row" as "settlement not posted yet" and failed closed on
     * every replay — the credit row is the durable signal that separates
     * the two cases.
     */
    @Test
    void refundedEvent_onAZeroCommissionRateSettlement_debitsWithoutAReversalRow() {
        UUID providerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        long priceCents = 5000L;

        PaymentIntentDetails completed = new PaymentIntentDetails(paymentIntentId, bookingId,
                consumerId, null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null,
                new java.math.BigDecimal("0.00"));
        PaymentIntentDetails refunded = new PaymentIntentDetails(paymentIntentId, bookingId,
                consumerId, null, "REFUNDED", "BOOKING", 25000L, "SAR", null, null,
                new java.math.BigDecimal("0.00"));
        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(completed), Optional.of(refunded));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", priceCents, "SAR",
                Instant.now(), Instant.now()));
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "A2 Zero Provider", "VERIFIED", ownerUserId)));

        // The settlement: the FULL price lands — the frozen 0.00 posts no
        // commission row at all.
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
        awaitBalance(providerId, priceCents);

        // The refund: the debit alone — no commission row existed, so no
        // reversal row exists either; the balance returns to zero.
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "REFUNDED")));
        awaitBalance(providerId, 0L);

        var balances = ledgerService.getBalancesForOwner(providerId);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).availableCents()).isZero();
        assertThat(balances.get(0).currency()).isEqualTo("SAR");

        // The whole statement: exactly the credit and its refund mirror —
        // NO commission row, NO reversal row (the zero commission's own
        // shape, the round-3 separation proven on the real slice).
        Page<LedgerEntry> statement = ledgerService.getStatementForOwner(providerId, PageRequest.of(0, 10));
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getSourceId, LedgerEntry::getEntryType, LedgerEntry::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(paymentIntentId, LedgerEntryType.PAYMENT_CREDIT, priceCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("refund-" + paymentIntentId).getBytes()),
                                LedgerEntryType.REFUND_DEBIT, priceCents));
    }

    /**
     * A2 + CodeRabbit round 3 (major — data integrity), the computed zero:
     * a booking whose HALF_UP fee rounds to nothing (4 minor units at the
     * live 0.10 rate) settles with NO commission row — the refund then
     * debits the credit alone and the balance returns to zero. The same
     * separation the frozen-rate zero proof pins, through the LIVE rate's
     * own arithmetic.
     */
    @Test
    void refundedEvent_onAComputedZeroCommission_debitsWithoutAReversalRow() {
        UUID providerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        long priceCents = 4L; // 4 × 0.10 = 0.4 — HALF_UP rounds to ZERO

        PaymentIntentDetails completed = new PaymentIntentDetails(paymentIntentId, bookingId,
                consumerId, null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null);
        PaymentIntentDetails refunded = new PaymentIntentDetails(paymentIntentId, bookingId,
                consumerId, null, "REFUNDED", "BOOKING", 25000L, "SAR", null, null, null);
        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(completed), Optional.of(refunded));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", priceCents, "SAR",
                Instant.now(), Instant.now()));
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "A2 Computed Zero Provider", "VERIFIED", ownerUserId)));

        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
        awaitBalance(providerId, priceCents);

        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "REFUNDED")));
        awaitBalance(providerId, 0L);

        Page<LedgerEntry> statement = ledgerService.getStatementForOwner(providerId, PageRequest.of(0, 10));
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getSourceId, LedgerEntry::getEntryType, LedgerEntry::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(paymentIntentId, LedgerEntryType.PAYMENT_CREDIT, priceCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("refund-" + paymentIntentId).getBytes()),
                                LedgerEntryType.REFUND_DEBIT, priceCents));
    }

    /**
     * A2 + CodeRabbit round 6 (nitpick, adopted — the ORDER twin): the
     * order-settlement's full refund reverses the seller's credit AND its
     * commission mirror on the REAL slice — the four movements, the
     * pre-settlement zero, the same proof the BOOKING journey carries, one
     * port hop away (the OrderSellerPort seam).
     */
    @Test
    void orderRefundedEvent_reversesBothTheCreditAndItsCommissionMirror() {
        UUID sellerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        long amountCents = 17400L;
        long commissionCents = 1740L; // 17400 × 0.10 HALF_UP

        PaymentIntentDetails completed = new PaymentIntentDetails(paymentIntentId, null,
                consumerId, null, "COMPLETED", "ORDER", amountCents, "SAR", orderId, null, null);
        PaymentIntentDetails refunded = new PaymentIntentDetails(paymentIntentId, null,
                consumerId, null, "REFUNDED", "ORDER", amountCents, "SAR", orderId, null, null);
        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(completed), Optional.of(refunded));
        when(orderSellerPort.sellerOf(orderId)).thenReturn(sellerId);
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(sellerId)).thenReturn(Optional.of(
                new ProviderSummary(sellerId, "A2 Order Seller", "VERIFIED", ownerUserId)));

        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
        awaitBalance(sellerId, amountCents - commissionCents);

        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "REFUNDED")));
        awaitBalance(sellerId, 0L);

        Page<LedgerEntry> statement = ledgerService.getStatementForOwner(sellerId, PageRequest.of(0, 10));
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getSourceId, LedgerEntry::getEntryType, LedgerEntry::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(paymentIntentId, LedgerEntryType.PAYMENT_CREDIT, amountCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_DEBIT, commissionCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("refund-" + paymentIntentId).getBytes()),
                                LedgerEntryType.REFUND_DEBIT, amountCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-reversal-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_REVERSAL_CREDIT, commissionCents));

        // The round-6 sign-fix proof rides the same journey: a stats window
        // covering the WHOLE refunded settlement nets the provider ZERO —
        // the reversal credit adds to the net exactly as the settlement's
        // commission subtracted (the pre-fix CASE read it as a debit: minus
        // twice the commission).
        var net = ledgerEntryRepository.sumNetCentsByCurrencyForProviderBetween(
                sellerId, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        assertThat(net)
                .as("the net window answers one currency row")
                .hasSize(1);
        assertThat(net.get(0)[0])
                .as("the net window's currency is the settlement's own")
                .isEqualTo("SAR");
        assertThat(((Number) net.get(0)[1]).longValue())
                .as("the net window over a fully refunded settlement is the honest zero")
                .isZero();
    }

    /**
     * A2 + CodeRabbit round 6 (nitpick, adopted — the LOAN twin): the
     * loan-fee settlement's full refund reverses the owner's credit AND its
     * commission mirror on the REAL slice — the LoanOwnerPort seam's own
     * four-movement journey to zero.
     */
    @Test
    void loanRefundedEvent_reversesBothTheCreditAndItsCommissionMirror() {
        UUID ownerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        UUID borrowerId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        long amountCents = 9000L;
        long commissionCents = 900L; // 9000 × 0.10 HALF_UP

        PaymentIntentDetails completed = new PaymentIntentDetails(paymentIntentId, null,
                borrowerId, null, "COMPLETED", "LOAN", amountCents, "SAR", null, loanId, null);
        PaymentIntentDetails refunded = new PaymentIntentDetails(paymentIntentId, null,
                borrowerId, null, "REFUNDED", "LOAN", amountCents, "SAR", null, loanId, null);
        when(paymentIntentLookupPort.findById(paymentIntentId))
                .thenReturn(Optional.of(completed), Optional.of(refunded));
        when(loanOwnerPort.ownerOf(loanId)).thenReturn(ownerId);
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(ownerUserId);
        when(providerLookupPort.findByUserId(ownerId)).thenReturn(Optional.of(
                new ProviderSummary(ownerId, "A2 Loan Owner", "VERIFIED", ownerUserId)));

        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
        awaitBalance(ownerId, amountCents - commissionCents);

        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "REFUNDED")));
        awaitBalance(ownerId, 0L);

        Page<LedgerEntry> statement = ledgerService.getStatementForOwner(ownerId, PageRequest.of(0, 10));
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getSourceId, LedgerEntry::getEntryType, LedgerEntry::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(paymentIntentId, LedgerEntryType.PAYMENT_CREDIT, amountCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_DEBIT, commissionCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("refund-" + paymentIntentId).getBytes()),
                                LedgerEntryType.REFUND_DEBIT, amountCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-reversal-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_REVERSAL_CREDIT, commissionCents));
    }

    /**
     * ADR-0013 (D2 — the commission freeze): the COMPLETED settlement of
     * an intent that froze a NON-default rate posts the FROZEN commission,
     * not the live configuration — the module-slice proof on the real
     * listener chain (the unit proof lives in LedgerPaymentEventListenerTest;
     * this one runs the actual @ApplicationModuleListener dispatch). The
     * intent carries the frozen 0.05 while the test profile's live
     * configuration stays 0.10 — the balance must land the 5% fee.
     */
    @Test
    void paymentCompletedEvent_postsTheFrozenCommissionRateNotTheLiveConfiguration() {
        UUID providerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        long priceCents = 5000L;
        long frozenCommissionCents = 250L; // 0.05 frozen at creation, NOT the live 0.10

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(
                new PaymentIntentDetails(paymentIntentId, bookingId, consumerId, null, "COMPLETED",
                        "BOOKING", 25000L, "SAR", null, null, new java.math.BigDecimal("0.05"))));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", priceCents, "SAR",
                Instant.now(), Instant.now()));
        // The guarded reads must be authorized (the existing journey's own
        // CodeRabbit #248 discipline): the owner mapping is stubbed so the
        // statement read below passes the AuthHelper ownership gate.
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(consumerId);
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "Frozen-Rate Provider", "VERIFIED", consumerId)));

        transactions.executeWithoutResult(tx ->
                events.publishEvent(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));

        awaitBalance(providerId, priceCents - frozenCommissionCents);

        var balances = ledgerService.getBalances(providerId);
        assertThat(balances).hasSize(1);
        assertThat(balances.get(0).availableCents())
                .as("the frozen 0.05 posts, not the live 0.10")
                .isEqualTo(priceCents - frozenCommissionCents);
        Page<LedgerEntry> statement = ledgerService.getStatementForOwner(providerId, PageRequest.of(0, 10));
        assertThat(statement.getContent())
                .extracting(LedgerEntry::getSourceId, LedgerEntry::getEntryType, LedgerEntry::getAmountCents)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(paymentIntentId, LedgerEntryType.PAYMENT_CREDIT, priceCents),
                        org.assertj.core.groups.Tuple.tuple(
                                UUID.nameUUIDFromBytes(("commission-" + paymentIntentId).getBytes()),
                                LedgerEntryType.COMMISSION_DEBIT, frozenCommissionCents));
    }

    /**
     * Plain poll loop (30s / 200ms) — no Awaitility dependency in this
     * reactor. R9: the poll reads the SAR row (the seeded booking's
     * currency) through the composite key.
     */
    /**
     * A3 (adversarial review 2026-11-10, owner-gate batch 6a — the documented
     * gate's OWN required proof): two concurrent webhook snapshots on the
     * SAME delivery batch (cumulative 300 and 700) must post EXACTLY the
     * highest cumulative to PSP_CLEARING — 700, never the pre-fix 1000
     * (the racing double-read of `findTopBy…(cumulative DESC)` both seeing
     * `previous = 0`). The transaction-scoped advisory lock
     * (pg_advisory_xact_lock over the per-batch key) serializes the whole
     * read-modify-write critical section; EITHER interleaving is legal —
     * 300-then-700 posts REFUND:300 (delta 300) + REFUND:700 (delta 400),
     * 700-then-300 posts REFUND:700 (delta 700) and the monotonicity guard
     * no-ops the 300 — and BOTH land exactly 700 total. The sum invariant
     * is the assertion (the pre-fix shape answers 1000).
     */
    @Test
    void concurrentDeliveryRefundSnapshotsPostExactlyTheHighestCumulative() throws Exception {
        UUID deliveryRequestId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        long fare = 1000L;

        transactions.executeWithoutResult(tx -> journalService.capture(
                new PaymentIntentDetails(paymentIntentId, null, consumerId, null,
                        "SUCCEEDED", "DELIVERY", fare, "SAR", null, null, null,
                        deliveryRequestId, 0L)));

        PaymentIntentDetails snapshot300 = new PaymentIntentDetails(paymentIntentId, null,
                consumerId, null, "PARTIALLY_REFUNDED", "DELIVERY", fare, "SAR",
                null, null, null, deliveryRequestId, 300L);
        PaymentIntentDetails snapshot700 = new PaymentIntentDetails(paymentIntentId, null,
                consumerId, null, "PARTIALLY_REFUNDED", "DELIVERY", fare, "SAR",
                null, null, null, deliveryRequestId, 700L);

        // T1 owns the exact production lock, posts 300, and keeps its transaction
        // open. T2 must be visible as an ungranted advisory-lock waiter before T1
        // is released; a start barrier alone would not prove this serialization.
        java.util.concurrent.CountDownLatch firstPostingCommittedToTransaction =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Integer> secondBackendPid =
                new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> first = pool.submit(() ->
                transactions.executeWithoutResult(tx -> {
                    holdDeliveryRefundLock(deliveryRequestId);
                    journalService.refund(snapshot300);
                    firstPostingCommittedToTransaction.countDown();
                    awaitLatch(releaseFirst, "First refund transaction was not released");
                }));
        java.util.concurrent.Future<?> second = null;
        try {
            assertThat(firstPostingCommittedToTransaction.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("first transaction must acquire the batch lock and post its 300 delta")
                    .isTrue();
            second = pool.submit(() ->
                    transactions.executeWithoutResult(tx -> {
                        Integer pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                        secondBackendPid.complete(pid);
                        journalService.refund(snapshot700);
                    }));
            int waiterPid = secondBackendPid.get(10, java.util.concurrent.TimeUnit.SECONDS);
            awaitAdvisoryLockWait(waiterPid, second);
            releaseFirst.countDown();
            first.get(60, java.util.concurrent.TimeUnit.SECONDS);
            second.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        Long posted = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_journal_postings "
                        + "WHERE source_type = 'DELIVERY' AND source_id = ? "
                        + "AND operation_key LIKE 'REFUND:%'",
                Long.class, deliveryRequestId);
        assertThat(posted)
                .as("A3: the waited transaction must read 300 after lock release and post only delta 400")
                .isEqualTo(700L);
        Long highestCumulative = jdbcTemplate.queryForObject(
                "SELECT MAX(cumulative_refund_minor) FROM ledger_journal_postings "
                        + "WHERE source_type = 'DELIVERY' AND source_id = ? "
                        + "AND operation_key LIKE 'REFUND:%'",
                Long.class, deliveryRequestId);
        assertThat(highestCumulative).isEqualTo(700L);
    }

    /**
     * A3: the completion and refund handlers share one transaction-scoped lock.
     * Whether the full refund commits before or after the completion event,
     * clearing nets to zero and the combined revenue/refund result carries
     * the courier's already-earned payable as a platform loss.
     */
    @Test
    void deliveryRefundAndCompletionProduceEquivalentNetResultInEitherEventOrder() {
        long fare = 1000L;
        long courierCompensation = 200L;
        UUID consumerId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();

        UUID completionFirstRequest = UUID.randomUUID();
        UUID completionFirstPayment = UUID.randomUUID();
        PaymentIntentDetails completionFirstCapture = new PaymentIntentDetails(completionFirstPayment, null,
                consumerId, null, "SUCCEEDED", "DELIVERY", fare, "SAR", null, null, null, completionFirstRequest, 0L);
        PaymentIntentDetails completionFirstRefund = new PaymentIntentDetails(completionFirstPayment, null,
                consumerId, null, "REFUNDED", "DELIVERY", fare, "SAR", null, null, null, completionFirstRequest, fare);
        DeliveryCompletedEvent completionFirstEvent = new DeliveryCompletedEvent(completionFirstRequest,
                completionFirstPayment, fare, "SAR", courierCompensation, Instant.now(), courierId);
        transactions.executeWithoutResult(tx -> journalService.capture(completionFirstCapture));
        transactions.executeWithoutResult(tx -> journalService.recognize(completionFirstEvent));
        transactions.executeWithoutResult(tx -> journalService.refund(completionFirstRefund));

        UUID refundFirstRequest = UUID.randomUUID();
        UUID refundFirstPayment = UUID.randomUUID();
        PaymentIntentDetails refundFirstCapture = new PaymentIntentDetails(refundFirstPayment, null,
                consumerId, null, "SUCCEEDED", "DELIVERY", fare, "SAR", null, null, null, refundFirstRequest, 0L);
        PaymentIntentDetails refundFirstRefund = new PaymentIntentDetails(refundFirstPayment, null,
                consumerId, null, "REFUNDED", "DELIVERY", fare, "SAR", null, null, null, refundFirstRequest, fare);
        DeliveryCompletedEvent refundFirstEvent = new DeliveryCompletedEvent(refundFirstRequest,
                refundFirstPayment, fare, "SAR", courierCompensation, Instant.now(), courierId);
        transactions.executeWithoutResult(tx -> journalService.capture(refundFirstCapture));
        transactions.executeWithoutResult(tx -> journalService.refund(refundFirstRefund));
        transactions.executeWithoutResult(tx -> journalService.recognize(refundFirstEvent));

        long completionFirstNet = journalNetForDelivery(completionFirstRequest);
        long refundFirstNet = journalNetForDelivery(refundFirstRequest);
        assertThat(completionFirstNet).isEqualTo(-courierCompensation);
        assertThat(refundFirstNet).isEqualTo(completionFirstNet);
        assertThat(journalAccountNet(completionFirstRequest, "PSP_CLEARING")).isZero();
        assertThat(journalAccountNet(refundFirstRequest, "PSP_CLEARING")).isZero();

        assertThat(journalPostingAmount(completionFirstRequest, "RECOGNIZE")).isEqualTo(fare);
        assertThat(journalDebitAccount(completionFirstRequest, "COURIER_PAYABLE")).isEqualTo("DELIVERY_REVENUE");
        assertThat(journalPostingCount(refundFirstRequest, "RECOGNIZE")).isZero();
        assertThat(journalPostingAmount(refundFirstRequest, "COURIER_PAYABLE")).isEqualTo(courierCompensation);
        assertThat(journalDebitAccount(refundFirstRequest, "COURIER_PAYABLE")).isEqualTo("DELIVERY_REFUNDS");
    }

    /**
     * A3 cross-operation race proof: refund() and recognize() must serialize
     * against the same batch lock. Either callback may win, but the committed
     * journal must converge to zero PSP clearing, zero customer advances,
     * the full courier payable, and the same net platform result.
     */
    @Test
    void concurrentDeliveryCompletionAndFullRefundConvergeOnOneNetResult() throws Exception {
        long fare = 1000L;
        long courierCompensation = 200L;
        UUID deliveryRequestId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();

        PaymentIntentDetails capture = new PaymentIntentDetails(paymentIntentId, null,
                consumerId, null, "SUCCEEDED", "DELIVERY", fare, "SAR",
                null, null, null, deliveryRequestId, 0L);
        PaymentIntentDetails fullRefund = new PaymentIntentDetails(paymentIntentId, null,
                consumerId, null, "REFUNDED", "DELIVERY", fare, "SAR",
                null, null, null, deliveryRequestId, fare);
        DeliveryCompletedEvent completion = new DeliveryCompletedEvent(deliveryRequestId,
                paymentIntentId, fare, "SAR", courierCompensation, Instant.now(), courierId);

        transactions.executeWithoutResult(tx -> journalService.capture(capture));

        // Refund owns the shared lock first and remains uncommitted. Completion
        // must wait on that exact advisory lock before it reads cumulative
        // refunds; this proves both event handlers participate in one lock protocol.
        java.util.concurrent.CountDownLatch refundPostingReady =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseRefund =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Integer> completionBackendPid =
                new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> refundFirst = pool.submit(() ->
                transactions.executeWithoutResult(tx -> {
                    holdDeliveryRefundLock(deliveryRequestId);
                    journalService.refund(fullRefund);
                    refundPostingReady.countDown();
                    awaitLatch(releaseRefund, "First full-refund transaction was not released");
                }));
        java.util.concurrent.Future<?> completionSecond = null;
        try {
            assertThat(refundPostingReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("refund transaction must own the lock and post the full refund")
                    .isTrue();
            completionSecond = pool.submit(() ->
                    transactions.executeWithoutResult(tx -> {
                        Integer pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                        completionBackendPid.complete(pid);
                        journalService.recognize(completion);
                    }));
            int waiterPid = completionBackendPid.get(10, java.util.concurrent.TimeUnit.SECONDS);
            awaitAdvisoryLockWait(waiterPid, completionSecond);
            releaseRefund.countDown();
            refundFirst.get(60, java.util.concurrent.TimeUnit.SECONDS);
            completionSecond.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            releaseRefund.countDown();
            pool.shutdownNow();
        }

        assertThat(journalNetForDelivery(deliveryRequestId))
                .as("A3: refund-first completion must leave only the earned courier compensation as a platform loss")
                .isEqualTo(-courierCompensation);
        assertThat(journalAccountNet(deliveryRequestId, "PSP_CLEARING"))
                .as("captured fare and full refund must clear exactly")
                .isZero();
        assertThat(journalAccountNet(deliveryRequestId, "DELIVERY_CUSTOMER_ADVANCES"))
                .as("customer advances must be fully discharged")
                .isZero();
        assertThat(journalAccountNet(deliveryRequestId, "DELIVERY_COURIER_PAYABLE"))
                .as("the courier's earned payable must survive the full customer refund")
                .isEqualTo(-courierCompensation);

        Long refundTotal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_journal_postings "
                        + "WHERE source_type = 'DELIVERY' AND source_id = ? "
                        + "AND operation_key LIKE 'REFUND:%'",
                Long.class, deliveryRequestId);
        assertThat(refundTotal).isEqualTo(fare);
        assertThat(journalPostingCount(deliveryRequestId, "RECOGNIZE")).isZero();
        assertThat(journalPostingAmount(deliveryRequestId, "COURIER_PAYABLE"))
                .isEqualTo(courierCompensation);
        assertThat(journalDebitAccount(deliveryRequestId, "REFUND:" + fare))
                .isEqualTo("DELIVERY_CUSTOMER_ADVANCES");
        assertThat(journalDebitAccount(deliveryRequestId, "COURIER_PAYABLE"))
                .isEqualTo("DELIVERY_REFUNDS");
    }

    private void holdDeliveryRefundLock(UUID deliveryRequestId) {
        // The UUID is generated by this test, not supplied by external input.
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(hashtextextended("
                + "'marketplace.delivery-refund-delta:' || '" + deliveryRequestId + "', 0))");
    }

    private void awaitAdvisoryLockWait(int backendPid, java.util.concurrent.Future<?> waiter) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (waiter.isDone()) {
                waiter.get();
                throw new AssertionError("The second transaction completed without waiting for the delivery advisory lock");
            }
            Boolean waiting = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_locks waiting "
                            + "JOIN pg_locks holding "
                            + "ON holding.locktype = waiting.locktype "
                            + "AND holding.database IS NOT DISTINCT FROM waiting.database "
                            + "AND holding.classid IS NOT DISTINCT FROM waiting.classid "
                            + "AND holding.objid IS NOT DISTINCT FROM waiting.objid "
                            + "AND holding.objsubid = waiting.objsubid "
                            + "AND holding.pid <> waiting.pid "
                            + "WHERE waiting.pid = ? AND waiting.locktype = 'advisory' "
                            + "AND waiting.granted = FALSE AND holding.granted = TRUE)",
                    Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The second transaction never appeared as an ungranted PostgreSQL advisory-lock waiter");
    }

    private static void awaitLatch(java.util.concurrent.CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException(failureMessage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        }
    }

    private long journalNetForDelivery(UUID deliveryRequestId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN credit_account = 'DELIVERY_REVENUE' THEN amount_minor ELSE 0 END), 0) "
                        + "- COALESCE(SUM(CASE WHEN debit_account = 'DELIVERY_REVENUE' THEN amount_minor ELSE 0 END), 0) "
                        + "- COALESCE(SUM(CASE WHEN debit_account = 'DELIVERY_REFUNDS' THEN amount_minor ELSE 0 END), 0) "
                        + "FROM ledger_journal_postings WHERE source_type = 'DELIVERY' AND source_id = ?",
                Long.class, deliveryRequestId);
        return value == null ? 0L : value;
    }

    private long journalAccountNet(UUID deliveryRequestId, String account) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN debit_account = ? THEN amount_minor ELSE 0 END), 0) "
                        + "- COALESCE(SUM(CASE WHEN credit_account = ? THEN amount_minor ELSE 0 END), 0) "
                        + "FROM ledger_journal_postings WHERE source_type = 'DELIVERY' AND source_id = ?",
                Long.class, account, account, deliveryRequestId);
        return value == null ? 0L : value;
    }

    private long journalPostingCount(UUID deliveryRequestId, String operationKey) {
        Long value=jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_journal_postings WHERE source_type='DELIVERY' AND source_id=? AND operation_key=?",
                Long.class,deliveryRequestId,operationKey);
        return value == null ? 0L : value;
    }

    private long journalPostingAmount(UUID deliveryRequestId, String operationKey) {
        Long value=jdbcTemplate.queryForObject(
                "SELECT amount_minor FROM ledger_journal_postings WHERE source_type='DELIVERY' AND source_id=? AND operation_key=?",
                Long.class,deliveryRequestId,operationKey);
        return value == null ? 0L : value;
    }

    private String journalDebitAccount(UUID deliveryRequestId, String operationKey) {
        return jdbcTemplate.queryForObject(
                "SELECT debit_account FROM ledger_journal_postings WHERE source_type='DELIVERY' AND source_id=? AND operation_key=?",
                String.class,deliveryRequestId,operationKey);
    }
    private void awaitBalance(UUID providerId, long expectedCents) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        long last = Long.MIN_VALUE;
        while (System.nanoTime() < deadline) {
            last = ledgerService.getBalances(providerId).stream()
                    .filter(b -> "SAR".equals(b.currency()))
                    .mapToLong(ProviderBalanceResponse::availableCents)
                    .findFirst().orElse(Long.MIN_VALUE);
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
