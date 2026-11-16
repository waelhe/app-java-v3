package com.marketplace.payments.spi;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.UUID;

/**
 * The unified finance-operator read model's search parameters (payments
 * plan Phase 1 — "an authorized finance operator can investigate any
 * origin by a single search"). Every field is OPTIONAL: a field left
 * {@code null} does not constrain; all present fields AND together.
 *
 * <p><b>{@code subjectId} is origin-agnostic by design:</b> it matches
 * whichever subject column the intent's origin carries — the operator
 * pastes ONE id (a booking, an ad campaign, an order, a loan) without
 * first knowing which kind it is. The payments module owns the mapping
 * from this contract to its own storage; no caller composes per-origin
 * queries.
 *
 * <p><b>{@code status} crosses the seam as a WORD, not an enum:</b> the
 * state machine's vocabulary is payments-owned and stays inside the
 * module (the admin module's allowlist grants {@code payments-spi}
 * only). The module's own search implementation validates the word and
 * answers the house 400 for a typo — never a silent zero-match page.
 *
 * <p><b>{@code origin} stays a word for the same reason it always
 * was one:</b> the W1 review-origin decision holds the column as data;
 * an unknown origin word honestly answers the empty page (origins the
 * running system does not know cannot have rows).
 *
 * <p>The window rides the house exclusive-end convention
 * ({@code [createdAfter, createdBefore)}).
 */
@NamedInterface("payments-spi")
public record PaymentIntentSearch(
        String origin,
        String status,
        UUID subjectId,
        UUID consumerId,
        Instant createdAfter,
        Instant createdBefore
) {

    /** The neutral search — no constraint at all. */
    public static PaymentIntentSearch neutral() {
        return new PaymentIntentSearch(null, null, null, null, null, null);
    }

    public boolean isNeutral() {
        return origin == null && status == null && subjectId == null
                && consumerId == null && createdAfter == null && createdBefore == null;
    }
}
