package com.marketplace.payments;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID>, JpaSpecificationExecutor<PaymentIntent>, RevisionRepository<PaymentIntent, UUID, Integer> {

    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

    /**
     * R4 (comprehensive-review-ar-fix plan §4/R4): the deterministic
     * per-booking intent search, scoped by status and ordered by the total
     * order {@code (createdAt, id)} DESC. The pre-fix unfiltered
     * {@code findByBookingId} returned an ARBITRARY row once the R4 defect
     * let several intents coexist for one booking (and would throw a
     * non-unique result once two rows race) — no unfiltered search on
     * {@code booking_id} remains. Callers scope their own contract:
     * the collectible attempt
     * ({@link PaymentIntentStatus#COLLECTIBLE}), the money-carrying
     * attempt for the financial/refund paths
     * ({@link PaymentIntentStatus#COLLECTED}).
     */
    Optional<PaymentIntent> findFirstByBookingIdAndStatusInOrderByCreatedAtDescIdDesc(
            UUID bookingId, Collection<PaymentIntentStatus> statuses);

    /**
     * R4: the creation guard's blocking search — the latest live row whose
     * status is NOT in the given retryable states. A row found here blocks
     * a fresh attempt: the plan's model is "a new intent for the booking
     * only after the previous FAILED or was CANCELLED", so both a live
     * collectible attempt AND a collected row (SUCCEEDED et al — the
     * booking is paid) reject the creation. The guard is the friendly
     * failure; the partial unique index {@code uq_payment_intents_one_active_attempt}
     * (V74) is the concurrency backstop for the race past it.
     */
    Optional<PaymentIntent> findFirstByBookingIdAndStatusNotInOrderByCreatedAtDescIdDesc(
            UUID bookingId, Collection<PaymentIntentStatus> statuses);

    /** Resolves a local intent from the remote PSP intent id (webhook path, V33). */
    Optional<PaymentIntent> findByPspIntentId(String pspIntentId);

    Page<PaymentIntentSummaryView> findAllSummariesBy(Pageable pageable);
}

