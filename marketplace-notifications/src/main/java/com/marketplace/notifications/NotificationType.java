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
 * <p>A-03 (official-compliance plan 0.6 — the dead {@code BookingConfirmedEvent}'s
 * delivery, the notification leg of the owner's identity rule "an event
 * without a listener is a measured defect"): {@code BOOKING_CONFIRMED} joins
 * as the tenth type — the same point addition (the V110 CHECK widens the
 * DB-side membership guard to match, V111 validates it under SHARE UPDATE
 * EXCLUSIVE alone — the V74/V75 precedent verbatim). The recipient is the
 * booking's consumer alone; the listener is the {@code BookingConfirmedEvent}
 * consumer the parallel contracts ledger recorded as "returning with round
 * A-03" (the late-lander crossing documented there).
 *
 * <p>B-08 (compliance plan 0.10 — the measured defect §3.4-8, unioned from
 * main 2026-10-08): {@code MESSAGE_RECEIVED} joins as the fourteenth type —
 * the same point addition (the V151 CHECK widens the DB-side membership
 * guard to match, V152 validates it under SHARE UPDATE EXCLUSIVE alone).
 * The recipient is the conversation's OTHER participant, resolved at the
 * source by the messaging publisher ({@code MessageReceivedEvent} carries
 * the arrival fact complete) — this listener never re-derives party facts;
 * one event is one notification, and the delivery rides the standing L22
 * per-type/channel preference matrix from day one, no new mechanism.
 *
 * <p>B-17 (compliance plan C.9 — the trust &amp; verification sidecar,
 * unioned from main 2026-10-08): {@code MEMBERSHIP_VERIFIED} joins as the
 * fifteenth type and {@code REPORT_RESOLVED} as the sixteenth — the same
 * point additions (the V158 CHECK widens the DB-side membership guard to
 * match, V159 validates it under SHARE UPDATE EXCLUSIVE alone). The
 * recipients are the measured journeys' own parties: the VERIFIED member
 * (the grant event fires on both {@code PENDING -> VERIFIED} and {@code
 * REJECTED -> VERIFIED} — the re-admission is a grant of the same signal)
 * and the report's REPORTER (the adjudication event fires on every
 * outcome — {@code RESOLVED} behind {@code HIDE_CONTENT} and {@code
 * DISMISSED} behind {@code DISMISS}; distinct from the author's {@code
 * CONTENT_MODERATED} alert, which is the hide fact alone). The event
 * records live in {@code shared/api} and the listener wiring LANDED (the
 * CodeRabbit round-1 adoption closing the CR-10 crossing: the B-08/
 * {@code MessageReceivedEvent} precedent — the record's shared/api
 * placement, no pom change anywhere, and {@code NotificationEventListener}
 * delivers on every publication).
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
    BOOKING_CONFIRMED,
    /**
     * A-11 (official-compliance plan §6 wave C — C.1: the order machine's
     * notification leg; the V110 widening precedent applied three types
     * later by V114/V115): the three order transitions whose buyer-facing
     * information crosses the module boundary. The recipient is the order's
     * consumer alone — the payload carries the id (the late-lander crossing
     * is recorded in the parallel contracts ledger, execution plan §5.3:
     * orders (Track A) publishes, the late-lander writes the listeners in
     * notifications, and the module's owner reviews).
     */
    ORDER_CONFIRMED,
    ORDER_FULFILLED,
    ORDER_CANCELLED,
    MESSAGE_RECEIVED,
    MEMBERSHIP_VERIFIED,
    REPORT_RESOLVED,
    /** Stage 8 (ADR-0004): the lending workflow's decision gates. */
    LOAN_REQUESTED,
    LOAN_APPROVED,
    LOAN_CANCELLED,
    /**
     * ADR-0009 (plan D-09 closure — the dispute cycle): the loan's freeze
     * and release facts. LOAN_DISPUTED reaches BOTH parties (the freeze),
     * LOAN_DISPUTE_RESOLVED reaches BOTH parties (the resume — a
     * REFUND_CONSUMER resolution terminates the loan instead, so the
     * existing LOAN_CANCELLED carries that outcome), and LOAN_CLOSED is
     * the settlement's terminal receipt for BOTH parties (including the
     * computed late-fee adjustment, ADR-0009). The V180 CHECK widening
     * also closes the measured stage-8 gap: the three LOAN_* decision-gate
     * types were Java-expressible since ADR-0004 but never joined the
     * DB-side membership guard (the exact defect class V110's header
     * documents — NotificationType stays the single source of truth for
     * the Java side, the constraint for the SQL side, the D-N7 discipline).
     */
    LOAN_DISPUTED,
    LOAN_DISPUTE_RESOLVED,
    LOAN_CLOSED
}
