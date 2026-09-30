package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public enum PaymentIntentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    /**
     * R4 (comprehensive-review-ar-fix plan §4/R4 — one collectible attempt
     * per booking): the states an intent can still COLLECT money in. The
     * partial unique index {@code uq_payment_intents_one_active_attempt}
     * (V74) admits at most ONE live row per booking in these states — the
     * decided model's database invariant; the scoped repository searches
     * and {@code createIntent}'s state guard read the same scope.
     */
    public static final List<PaymentIntentStatus> COLLECTIBLE = List.of(CREATED, PROCESSING);

    /**
     * R4: the states where money HAS MOVED for the booking — the intent
     * collected (and may be mid-refund). The deterministic financial
     * searches ({@code findFirstByBookingIdAndStatusIn...}, latest by
     * {@code (createdAt, id)}) are scoped to these states for the refund
     * paths, and a live row here also blocks a fresh attempt: the booking
     * is paid — another collectible intent would re-open the double-charge
     * window this wave closes.
     */
    public static final List<PaymentIntentStatus> COLLECTED =
            List.of(SUCCEEDED, PARTIALLY_REFUNDED, REFUNDED);

    /**
     * R4: the states after which a NEW attempt may be created — the plan's
     * own model sentence ("a new intent for the booking only after the
     * previous FAILED or was CANCELLED"). Every other live row blocks the
     * creation guard ({@code findFirstByBookingIdAndStatusNotIn}).
     */
    public static final List<PaymentIntentStatus> RETRYABLE = List.of(FAILED, CANCELLED);

    public static final java.util.Map<PaymentIntentStatus, Set<PaymentIntentStatus>> TRANSITIONS =
            Collections.unmodifiableMap(java.util.Map.of(
                    CREATED, EnumSet.of(PROCESSING, CANCELLED),
                    PROCESSING, EnumSet.of(SUCCEEDED, FAILED),
                    SUCCEEDED, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED),
                    FAILED, EnumSet.noneOf(PaymentIntentStatus.class),
                    CANCELLED, EnumSet.noneOf(PaymentIntentStatus.class),
                    REFUNDED, EnumSet.noneOf(PaymentIntentStatus.class),
                    PARTIALLY_REFUNDED, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED)
            ));

    public void validateTransitionTo(PaymentIntentStatus target) {
        Set<PaymentIntentStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new ConflictException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}
