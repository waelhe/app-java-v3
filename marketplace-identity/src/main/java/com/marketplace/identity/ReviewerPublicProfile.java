package com.marketplace.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — G28/G29): the public reviewer page's response —
 * exactly the fields the plan's own list names ("اسم، طابع انضمام، عدّاد
 * مراجعات موثقة/عاملة، أوسمته") plus the helpful-vote total the helpful
 * badge derives from (a measured public fact — the per-review
 * {@code helpfulCount} is already public on every review row).
 *
 * <p>Field discipline (the {@code ProviderPublicPageResponse} whitelist
 * rule — "the record cannot leak what it does not declare"): no email, no
 * subject, no role — the profile fields a public reviewer surface renders
 * and nothing else. The {@code reviewerId} echo is the page's own key (the
 * caller's path parameter), the click-target the review rows carry.
 *
 * <p>The reviewer's ACTIVITY (his published reviews, paged) is NOT embedded:
 * the existing public {@code GET /api/v1/reviews/reviewer/{reviewerId}}
 * surface already serves it under the SAME key (users.id — no id-space
 * seam to close, unlike the provider page whose embedded reviews block
 * had to translate profile-id to user-id through W1's port). One read
 * surface per fact, no duplication.
 *
 * @param reviewerId          the page's own key (users.id)
 * @param displayName         the pseudonym-honouring public name
 * @param joinedAt            when the account was created (BaseEntity's
 *                            audited {@code created_at})
 * @param verifiedReviewCount published reviews born from completed bookings
 * @param organicReviewCount  published general reviews
 * @param helpfulVoteCount    cumulative helpful votes his reviews received
 * @param badges              the derived badge labels (G29 — never stored)
 */
public record ReviewerPublicProfile(
        UUID reviewerId,
        String displayName,
        Instant joinedAt,
        long verifiedReviewCount,
        long organicReviewCount,
        long helpfulVoteCount,
        List<ReviewerBadge> badges
) {
}
