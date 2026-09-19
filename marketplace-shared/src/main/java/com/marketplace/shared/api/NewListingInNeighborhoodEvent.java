package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L46 (neighborhood community plan §5 — the community realestate bridge):
 * published by the community module's bridge listener for EVERY member of
 * the activated listing's neighborhood (the publisher's own membership
 * excepted — the conflict-of-interest exclusion lives in the community
 * side's consumption of {@code ListingActivatedEvent}), consumed by the
 * notifications module for the {@code NEW_LISTING_IN_NEIGHBORHOOD} alert.
 *
 * <p>The Modulith house pattern ({@code SavedSearchMatchedEvent} /
 * {@code PostCommentedEvent} precedents — both per-user alert events):
 * identifiers only, one event per recipient, published inside the bridge
 * listener's own transaction so the publication registry entries commit
 * atomically with the member resolution unit — a failure anywhere in the
 * listener rolls them all back together, and the
 * {@code @ApplicationModuleListener} consumer runs AFTER_COMMIT in its
 * own REQUIRES_NEW unit, so a failed notification delivery leaves THAT
 * member's registry entry incomplete for the framework's retry (the
 * plan's criterion 3: "فشل تسليم إشعار لا يفقد المطابقة").
 *
 * <p><b>The recipient is a users.id-space id</b> — the membership's
 * {@code user_id} (the same plain-UUID discipline every community column
 * carries), so the notifications side uses it directly as the recipient
 * with no profile resolution (the same seam
 * {@code SavedSearchMatchedEvent} and {@code PostCommentedEvent} pin).
 *
 * <p><b>Fan-out is linear by design</b> (the plan's D-C1 debt, declared
 * with its measured closure point — member counts above the threshold
 * move to batching/digest): one event per member keeps every delivery
 * independently retryable, which is exactly what criterion 3 tests.
 */
public record NewListingInNeighborhoodEvent(
        UUID recipientId,
        UUID listingId
) {
}
