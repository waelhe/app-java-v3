package com.marketplace.shared.api;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves the sources a user explicitly follows — the followed-sources
 * rail's relationship leg (AC-20-05: "المتابعة" لا تُستبدل بـ"لك"،
 * ويستطيع المستخدم معرفة مصادر محتوى المتابعة).
 *
 * <p><b>The single-home rule (no dual writes, no manual migrations):</b>
 * PROVIDER follows live in {@code provider_follows} (V93 — the existing
 * write path keeps its home); USER and GROUP follows live in the
 * generalized {@code follows} table (the discovery wave). The identity
 * module's adapter UNIONS both at read time — each follow type has
 * exactly one home, the old endpoints keep working untouched, and the
 * rail reads one honest union.</p>
 *
 * <p>House pattern: interface in shared-api, marketplace-identity
 * implements it (it owns both follow stores), discovery injects it.</p>
 */
public interface FollowedSourcesPort {

    /** @return the user's live follows — the closed type vocabulary:
     *         {@code USER}, {@code GROUP}, {@code PROVIDER} */
    Set<FollowedSource> followedSources(UUID userId);

    /** One live follow. {@code sourceId} sits in the type's own id space:
     *  USER → users.id, GROUP → neighborhood_groups.id, PROVIDER → the
     *  provider's USER id (V93's provider_user_id space, which is exactly
     *  what ListingActivatedEvent carries). */
    record FollowedSource(String type, UUID sourceId) {
    }
}
