package com.marketplace.disputes;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.disputes.spi.DisputesContentPurgeAdapter;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.LoanPartyProvider;
import com.marketplace.shared.api.PaymentRefundPort;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class DisputesModuleIntegrationTest {

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

    /**
     * L24: the refund port is implemented in marketplace-payments — outside
     * this module slice — so the slice replaces it at the boundary (the
     * same @MockitoBean treatment BookingParticipantProvider gets).
     */
    @MockitoBean
    PaymentRefundPort paymentRefundPort;

    /**
     * The loan party port is implemented in marketplace-lending — outside
     * this module slice — so the slice replaces it at the boundary (the
     * same @MockitoBean treatment PaymentRefundPort gets; the ADR-0009
     * loan-freeze contract added the port to the service's constructor —
     * the CI-measured 2026-10-12 first run of this slice).
     */
    @MockitoBean
    LoanPartyProvider loanPartyProvider;

    @Autowired
    private DisputeService disputeService;

    @Autowired
    private DisputeRepository disputeRepository;

    @Autowired
    private DisputesContentPurgeAdapter disputesContentPurgeAdapter;

    @Autowired
    private EntityManager entityManager;

    @Test
    void contextLoads() {
    }

    /**
     * The booking-scoped read's dual assertion (the E4 closure of the
     * 2026-11-10 adversarial review: the former shape asserted an empty list
     * against a database with NO disputes at all — emptiness indistinguishable
     * from a broken booking filter, and a tolerant {@code any()} booking stub
     * masking the per-booking authorization). Seeds TWO disputes across TWO
     * bookings (a second consumer's row included), then proves BOTH sides of
     * the scope: the requested booking returns EXACTLY its own dispute (the
     * seeded row alone — a filter that ignores the booking argument would
     * leak the second consumer's row here), and an unknown-but-participating
     * booking stays empty (emptiness comes from the scope, not from an empty
     * database). The booking stub is argument-specific — the participant gate
     * stays honest per booking.
     */
    @Test
    @Transactional
    void listForBooking_scopesToTheRequestedBooking_only_andUnknownBookingsStayEmpty() {
        var auth = Mockito.mock(Authentication.class);
        var userId = UUID.randomUUID();
        var otherConsumer = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var otherBooking = UUID.randomUUID();
        var unknownBooking = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(
                new BookingInfo(UUID.randomUUID(), userId, "CONFIRMED",
                        1000L, "USD", java.time.Instant.now(), java.time.Instant.now()));
        when(bookingParticipantProvider.getBookingInfo(unknownBooking)).thenReturn(
                new BookingInfo(UUID.randomUUID(), userId, "CONFIRMED",
                        1000L, "USD", java.time.Instant.now(), java.time.Instant.now()));

        Dispute owned = disputeRepository.save(
                Dispute.openBooking(bookingId, userId, "the consumer's authored damage report"));
        disputeRepository.save(
                Dispute.openBooking(otherBooking, otherConsumer, "the second consumer's report"));
        entityManager.flush();

        var scoped = disputeService.listForBooking(bookingId, auth);
        assertThat(scoped).as("the requested booking returns EXACTLY its own dispute")
                .hasSize(1);
        assertThat(scoped.getFirst().getId()).as("the returned row is the seeded booking's dispute")
                .isEqualTo(owned.getId());

        assertThat(disputeService.listForBooking(unknownBooking, auth))
                .as("an unknown participating booking stays empty — emptiness from the scope, "
                        + "not from an empty database")
                .isEmpty();
    }

    /**
     * Row 3 (agent findings 2026-10-26 — CWE-362, the finding's own
     * scenario verbatim): a load-before-purge, flush-after-purge
     * resolution must NOT be able to commit the original reason. The
     * pre-fix purge left the optimistic-lock version untouched, so the
     * stale managed entity's flush still matched its version predicate
     * and restored the erased text (plus a new Envers revision). With the
     * purge advancing {@code version} atomically, the stale flush matches
     * zero rows and fails with the framework's optimistic-lock exception
     * — the erasure cannot be undone by a concurrent writer.
     */
    @Test
    @Transactional
    void contentPurge_invalidatesAStaleManagedDisputeSoTheErasureCannotBeUndone() {
        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        Dispute seeded = disputeRepository.save(
                Dispute.openBooking(bookingId, consumerId, "the consumer's authored damage report"));
        assertThat(seeded.getReason()).isEqualTo("the consumer's authored damage report");

        // The seed must reach the database before the purge runs: the
        // entity's id is assigned in code (no identity insert), so
        // persist() defers the INSERT, and the purge's native UPDATE
        // rides JdbcTemplate — a channel Hibernate does not auto-flush
        // for — so without this flush the UPDATE matches zero rows and
        // the whole scenario is vacuous (measured: purged == 0 on CI).
        entityManager.flush();

        // The concurrent resolution's load: a managed entity holding the
        // pre-purge version.
        Dispute staleLoaded = entityManager.find(Dispute.class, seeded.getId());
        assertThat(staleLoaded.getVersion()).isEqualTo(0L);

        // The authorized content purge — native SQL on the same row, same
        // transaction: the text dies AND the version advances behind the
        // persistence context's back.
        int purged = disputesContentPurgeAdapter.purgeAuthoredTexts(consumerId);
        assertThat(purged).isGreaterThanOrEqualTo(1);

        // The stale writer mutates and flushes: the pre-fix code committed
        // the erased reason back into the live row (and Envers recorded a
        // new revision carrying it). Now the flush must fail instead.
        staleLoaded.resolve(com.marketplace.shared.api.DisputeResolution.REFUND_CONSUMER);
        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOf(jakarta.persistence.OptimisticLockException.class);
    }
}
