package com.marketplace.media;

/**
 * L48 (the Nextdoor-2026 completeness wave — gap #2, post images): the
 * media line's target vocabulary — the discriminator that pins what a
 * {@code media_assets} row is attached to. Until L48 exactly one target
 * existed (the provider listing, V32); the community plan's D-C3 decision
 * registered the generalization question and pointed here — one media
 * pipeline (presigned upload + HeadObject verification + deterministic
 * L28 thumbnail) attached to a domain target, with the target explicit
 * per row.
 *
 * <p>Stored as the {@code owner_kind} VARCHAR column with a CHECK
 * membership guard (V76, the V61 D-N7 pattern): the database owns the
 * vocabulary exactly like {@code PostCategory} owns the feed's.
 *
 * <p>The kind is the write-path's ownership gate selector (the A1 fact
 * that {@code provider_id} is a user id holds for both — for a LISTING
 * row it resolves through the provider profile, for a POST row it is the
 * post author's user id compared directly, because a member author need
 * not carry a provider profile at all).
 */
public enum MediaOwnerKind {

    /** The V32 target: a provider listing's photo (provider-scoped). */
    LISTING,

    /** The L48 target: a neighborhood post's photo (member-scoped). */
    POST
}
