package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7 (the corrected GraphQL plan §11-Phase-7 + the journey inventory
 * #599 §5 item 3 + the readiness doc's P0-1 closure — «تفاصيل منشور/
 * مصدر: قراءة مركبة تجمع التفاصيل والتعليقات والوسائط العامة المسموح
 * بها»): the post comment's cross-module read projection — the bounded
 * page the post-detail composite's GraphQL field consumes, on the
 * {@code NeighborhoodFeedPostView}/{@code PostLookupAdapter} house
 * pattern: the transport view lives here in shared-api, the community
 * module's adapter maps its internal comment view into it, and the
 * consumer (the app's DGS data fetcher) injects the owner's declared
 * port — no module boundary is crossed in code.
 *
 * <p><b>The field set IS the REST comment read view's own set</b> — the
 * same-journey comparison the inventory's acceptance gate pins («مقارنة
 * مع REST للرحلة نفسها»): nothing narrower (the detail screen consumes
 * every field the REST comments read serves), nothing wider (no field
 * the owning read does not already carry). The author stays an opaque
 * user id (the identity seams own any resolution — «معرفة المعرّف لا
 * يمنح إذنًا لقراءة البيانات الخاصة», the inventory §8), the comment
 * never re-projects its post's own state (the comment is reached through
 * the post gate — {@code PostCommentView}'s own projection discipline),
 * and the instants are {@code Instant} exactly as the REST view carries
 * them (the GraphQL surface renders ISO-8601 strings at ITS boundary —
 * the conversion lives in that surface's mapper, never here).</p>
 *
 * <p><b>Framework-neutral on purpose</b> (the 2026-09-25 port-audit
 * rule): the Spring Data {@code Page} stays inside the owner's adapter;
 * this view rides {@link PagedResponse} across the boundary.</p>
 *
 * @param id        the comment's id in the community module's id space
 * @param postId    the post's id the comment belongs to (the comment is
 *                  reached through the post gate — the projection never
 *                  re-projects the post itself)
 * @param authorId  the author's plain user id (opaque, never a
 *                  cross-module relation — the V32 discipline)
 * @param body      the authored body (bounded at 2000 characters,
 *                  {@code @NotBlank} on the comment surface — always
 *                  present on a stored comment)
 * @param createdAt the comment's publish moment
 * @param updatedAt the comment's version moment
 */
public record NeighborhoodPostCommentView(
        UUID id,
        UUID postId,
        UUID authorId,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
}
