package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): published
 * by the catalog module's {@code CatalogService.activate()} inside the
 * activation transaction, consumed by the search module's saved-search
 * matcher (and, when its window opens, by the community layer's L46
 * neighborhood bridge — <b>one publisher, many consumers</b>, the
 * Modulith fan-out the community plan's D-N6/L46 section documents as
 * "ناشر واحد مستهلكان").
 *
 * <p>The Modulith house pattern ({@code BookingCreatedEvent} /
 * {@code MediaUploadedEvent} / {@code ListingLeadCreatedEvent} precedent):
 * a shared-api record carrying identifiers only, published inside the
 * writer's transaction so the Event Publication Registry writes its
 * entries atomically with the business write; {@code @ApplicationModuleListener}
 * consumers run after commit in their own transaction with the framework's
 * retry.
 *
 * <p><b>Scope (the plan's own wording):</b> the event is owned by
 * {@code activate()} <em>exclusively</em> — renewal ({@code renew()}) is a
 * different transition (an EXPIRED pause re-entering ACTIVE for the same
 * audience) and does not re-fire the alert; a re-activated PAUSED listing
 * goes through {@code activate()} again and does.
 *
 * <p><b>The id-space fact (A1/V2, measured):</b> {@code providerId} is
 * the listing's provider — {@code provider_listings.provider_id} lives in
 * the <em>users.id</em> space, so consumers use it directly as a
 * user-space reference (the same fact {@code ListingLeadCreatedEvent}
 * pins). The matcher needs it for the stay-window branch: a windowed saved
 * search only matches listings of providers with an available slot.
 */
public record ListingActivatedEvent(
        UUID listingId,
        UUID providerId
) {
}
