package com.marketplace.shared.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6 (the corrected GraphQL plan §11-Phase-6 — the first composite
 * user journey, the journey inventory #599 §7 stage 4: «قراءة مركبة
 * للاكتشاف/موجز الحي»): the neighborhood live feed's cross-module read
 * projection — the bounded page the home composite's GraphQL field
 * consumes, on the {@code PostLookupAdapter}/{@code CommunityPostSearchPort}
 * house pattern: the transport view lives here in shared-api, the
 * community module's adapter maps its internal feed view into it, and
 * the consumer (the app's DGS data fetcher) injects the owner's declared
 * port — no module boundary is crossed in code.
 *
 * <p><b>The field set IS the REST feed view's own set</b> — the
 * same-journey comparison the inventory's acceptance gate pins («مقارنة
 * مع REST للرحلة نفسها»): nothing narrower (the home screen consumes
 * every field the REST feed serves), nothing wider (no field the owning
 * read does not already carry). The author stays an opaque user id (the
 * identity seams own any resolution), {@code category}/{@code status}/
 * {@code lostFoundState} stay the domain's Strings exactly as the REST
 * view carries them (the GraphQL surface's SDL mirrors them as typed
 * enums at ITS boundary — the conversion lives in that surface's
 * mapper, never here), {@code reactionsCount}/{@code reactedByMe} are
 * the caller-scoped read model's own facts (the grouped count and the
 * caller's own live voice — the L47 widening the REST feed already
 * carries), and {@code media} are the media module's presigned display
 * URLs as the feed read computed them (no object key, no status, no
 * storage fact crosses the boundary — the L48 discipline verbatim).</p>
 *
 * <p><b>Framework-neutral on purpose</b> (the 2026-09-25 port-audit
 * rule): the Spring Data {@code Page} stays inside the owner's adapter;
 * this view rides {@link PagedResponse} across the boundary.</p>
 *
 * @param id                   the post's id in the community module's id space
 * @param authorId             the author's plain user id (opaque, never a
 *                             cross-module relation — the V32 discipline)
 * @param locationId           the geo tree node (level-3 neighborhood) the
 *                             post is scoped to
 * @param category             the domain category vocabulary as String
 *                             (GENERAL/CLASSIFIED/LOST_FOUND/RECOMMENDATION/
 *                             QUESTION/REQUEST — mirrored as a typed enum
 *                             at the consuming GraphQL boundary)
 * @param title                the authored title (bounded at 200 characters)
 * @param body                 the authored body (bounded at 2000 characters,
 *                             {@code @NotBlank} on the publish surface —
 *                             always present on a stored post)
 * @param status               the moderation status (VISIBLE — the feed read
 *                             answers visible posts only; a hidden post is
 *                             absent, never rendered)
 * @param declaredCommercial   the author's own DSA Art. 26(2) declaration
 *                             (ADR-0011 — the marking travels on every read)
 * @param lostFoundState       present ONLY on LOST_FOUND posts (V171's own
 *                             shape: null for every other category)
 * @param reactionsCount       the live thanks count (the grouped count over
 *                             the page's ids — the same for every reader)
 * @param reactedByMe          the caller's own live voice (the projection's
 *                             one per-reader field)
 * @param media                the post's photos as the feed read renders them
 *                             (presigned GET URLs + position order; empty
 *                             for a post with no photos)
 * @param createdAt            the publish moment
 * @param updatedAt            the post's version moment
 */
public record NeighborhoodFeedPostView(
        UUID id,
        UUID authorId,
        UUID locationId,
        String category,
        String title,
        String body,
        String status,
        boolean declaredCommercial,
        String lostFoundState,
        long reactionsCount,
        boolean reactedByMe,
        List<NeighborhoodFeedMediaView> media,
        Instant createdAt,
        Instant updatedAt
) {
    public NeighborhoodFeedPostView {
        media = List.copyOf(media);
    }
}
