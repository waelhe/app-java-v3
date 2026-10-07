package com.marketplace.notifications;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the notification types a
 * preference is expressed against. These are exactly the types
 * {@code NotificationService} emits today from its event points
 * (booking created / payment state changed / lead received) — the single
 * source of truth for the type strings, so a preference row can never
 * drift from a delivered notification type.
 *
 * <p>L34 (realestate systems plan §5 — lead capture): {@code LEAD_RECEIVED}
 * joins as the third type — a point addition on the standing pattern
 * ("القناة الجديدة إضافة نقطة واحدة"): the enum is the single source of
 * truth, the per-type/channel preference machinery (L22) governs its
 * delivery from day one with no new mechanism.
 *
 * <p>L35 (realestate systems plan §5 — saved searches and alerts):
 * {@code SAVED_SEARCH_MATCH} joins as the fourth type — the same point
 * addition (the V55 CHECK widens the DB-side membership guard to match).
 *
 * <p>L42 (neighborhood community plan §5 — the posts/feed/comments layer):
 * {@code POST_COMMENTED} joins as the fifth type — the same point addition
 * ("نقطة إضافة واحدة" D-N12; the V62 CHECK widens the DB-side membership
 * guard to match). The recipient is the post's author; the self-comment
 * skip is the listener's own policy, not this enum's concern.
 *
 * <p>L46 (neighborhood community plan §5 — the community realestate
 * bridge): {@code NEW_LISTING_IN_NEIGHBORHOOD} joins as the sixth type —
 * the same point addition (the V63 CHECK widens the DB-side membership
 * guard to match). The recipient is an ACTIVE member of the activated
 * listing's neighborhood; the publisher's own membership is excluded by
 * the community side's bridge listener, not by this enum.
 *
 * <p>L45 (neighborhood community plan §5 — the moderation &amp; reports
 * layer): {@code CONTENT_MODERATED} joins as the seventh type — the same
 * point addition ("نقطة إضافة" — the plan's own wording for this
 * layer's notification; the V65 CHECK widens the DB-side membership
 * guard to match). The recipient is the moderated content's author; the
 * event fires on the real VISIBLE→HIDDEN transition alone (an
 * already-hidden or author-deleted target carries no new fact), and the
 * one-real-hide-one-alert policy lives in the community side's resolve
 * command, not in this enum.
 *
 * <p>L47 (the Nextdoor-2026 completeness wave — gap #1, the reactions
 * layer): {@code POST_REACTED} joins as the eighth type — the same point
 * addition (the V74 CHECK widens the DB-side membership guard to match,
 * V75 validates it under SHARE UPDATE EXCLUSIVE alone). The recipient is
 * the thanked post's author; the self-thank skip is the listener's own
 * policy (the {@code PostCommentedEvent} criterion-4 precedent), not
 * this enum's concern.
 *
 * <p>W4 (yelp-level plan §5 — the reviewer identity &amp; engagement wave,
 * G21): {@code FOLLOWED_PROVIDER_NEW_LISTING} joins as the ninth type —
 * the same point addition (the V93 CHECK widens the DB-side membership
 * guard to match, V94 validates it under SHARE UPDATE EXCLUSIVE alone).
 * The recipient is a follower of the listing's provider — the identity
 * module's follow bridge pre-scopes one {@code FollowedProviderNewListingEvent}
 * per follower per listing announcement (the alert ledger's structural
 * "exactly once"), so one event is one notification; the delivery rides
 * the standing L22 per-type/channel preference matrix from day one, no
 * new mechanism.
 *
 * <p>B-08 (compliance plan 0.10 — the measured defect §3.4-8): {@code
 * MESSAGE_RECEIVED} joins as the tenth type — the same point addition
 * (the V151 CHECK widens the DB-side membership guard to match, V152
 * validates it under SHARE UPDATE EXCLUSIVE alone). The recipient is the
 * conversation's OTHER participant, resolved at the source by the
 * messaging publisher ({@code MessageReceivedEvent} carries the arrival
 * fact complete) — this listener never re-derives party facts; one event
 * is one notification, and the delivery rides the standing L22
 * per-type/channel preference matrix from day one, no new mechanism.
 *
 * <p>B-17 (compliance plan C.9 — the trust &amp; verification sidecar):
 * {@code MEMBERSHIP_VERIFIED} joins as the eleventh type and {@code
 * REPORT_RESOLVED} as the twelfth — the same point additions (the V158
 * CHECK widens the DB-side membership guard to match, V159 validates it
 * under SHARE UPDATE EXCLUSIVE alone). The recipients are the measured
 * journeys' own parties: the VERIFIED member (the grant event fires on
 * both {@code PENDING -> VERIFIED} and {@code REJECTED -> VERIFIED} —
 * the re-admission is a grant of the same signal) and the report's
 * REPORTER (the adjudication event fires on every outcome — {@code
 * RESOLVED} behind {@code HIDE_CONTENT} and {@code DISMISSED} behind
 * {@code DISMISS}; distinct from the author's {@code CONTENT_MODERATED}
 * alert, which is the hide fact alone). The event records sit module-local
 * in community pending CR-10 (the B-08/{@code MessageReceivedEvent}
 * precedent: the listener's import needs the record in {@code shared/api}
 * — the house convention, no pom change anywhere), so the listener wiring
 * rides that CR while these delivery handlers land now.
 */
public enum NotificationType {
    BOOKING_CREATED,
    PAYMENT_STATE,
    LEAD_RECEIVED,
    SAVED_SEARCH_MATCH,
    POST_COMMENTED,
    NEW_LISTING_IN_NEIGHBORHOOD,
    CONTENT_MODERATED,
    POST_REACTED,
    FOLLOWED_PROVIDER_NEW_LISTING,
    MESSAGE_RECEIVED,
    MEMBERSHIP_VERIFIED,
    REPORT_RESOLVED
}
