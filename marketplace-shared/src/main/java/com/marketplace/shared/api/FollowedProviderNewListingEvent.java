package com.marketplace.shared.api;

import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — the reviewer identity &amp; engagement wave, G21):
 * published by the identity module's follow bridge for every follower of the
 * activated listing's provider, consumed by the notifications module for the
 * {@code FOLLOWED_PROVIDER_NEW_LISTING} alert — the third declared consumer
 * chain of the catalog's {@code ListingActivatedEvent} (the saved-search
 * matcher and the community neighborhood bridge proved the fan-out: "ناشر
 * واحد مستهلكان", one publisher, many consumers).
 *
 * <p>The Modulith house pattern ({@code SavedSearchMatchedEvent} /
 * {@code NewListingInNeighborhoodEvent} precedents — both per-user alert
 * events): identifiers only, one event per recipient, published inside the
 * bridge listener's own transaction so the publication registry entries
 * commit atomically with the follow-alert ledger writes — a failure anywhere
 * in the listener rolls them all back together, and the
 * {@code @ApplicationModuleListener} consumer runs AFTER_COMMIT in its own
 * REQUIRES_NEW unit, so a failed notification delivery leaves THAT
 * follower's registry entry incomplete for the framework's retry (the
 * listener-side retry contract every house listener documents).
 *
 * <p><b>The recipient is a users.id-space id</b> — the follower's
 * {@code user_id} (the same plain-UUID discipline every cross-module column
 * carries), so the notifications side uses it directly as the recipient with
 * no profile resolution (the same seam {@code NewListingInNeighborhoodEvent}
 * pins).
 *
 * <p><b>Exactly one alert per (follower, listing) ever</b> (the plan's W4
 * acceptance: "المتابعة تطلق تنبيهًا واحدًا محترمًا للتفضيل"): the bridge's
 * alert ledger (V93's {@code provider_follow_alerts}, the
 * {@code saved_search_matches} precedent) makes a re-run of the bridge — a
 * registry re-delivery of the same activation — insert nothing and publish
 * nothing, so this event fires once per follower per listing announcement
 * regardless of at-least-once delivery (D-E10).
 */
public record FollowedProviderNewListingEvent(
        UUID recipientId,
        UUID listingId
) {
}
