package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

/**
 * W1 (yelp-level plan §4.5): the disclosed review-lookup seam for the two
 * cross-module consumers of the reviews aggregate.
 *
 * <p><b>Port here, adapter in the reviews module (the {@code ReviewStatsPort}
 * pattern, SYSTEM.md §6):</b> the community module's report target gate and
 * hide path plus the media module's review-media ownership gate depend on
 * {@code shared :: shared-api} only — never on the reviews module's
 * internals. Modulith then refuses any consumer that reaches past this
 * interface.
 *
 * <p><b>Id space (A1, measured):</b> the returned author id is the review's
 * {@code reviewer_id} — the {@code users.id} space every cross-module id
 * column carries. Callers compare it directly against the caller's user id;
 * no profile resolution anywhere.
 */
public interface ReviewLookupPort {

    /**
     * The author of a live review whose moderation status is
     * {@code PUBLISHED} — the surface everyone can see. Empty for an
     * unknown, soft-deleted, pending or moderator-hidden row: the caller's
     * honest 404 (the review's own visibility contract, the community
     * report gate's ruling).
     */
    Optional<UUID> findVisibleAuthorId(UUID reviewId);

    /**
     * The author of a live review in ANY moderation status — the ownership
     * fact behind the review-media gates (an author manages media on their
     * own pending review exactly as on a published one). Empty only for an
     * unknown or soft-deleted row.
     */
    Optional<UUID> findAuthorId(UUID reviewId);

    /**
     * The moderation hide (§4.5): flips a {@code PUBLISHED} review to
     * {@code HIDDEN_BY_MODERATOR} and publishes the existing update event so
     * the stored provider average recomputes without the row. Returns the
     * review's author id when the real transition happened — the caller
     * alerts that author; empty for an unknown, soft-deleted, already-hidden
     * or pending row (the documented skip: the hide goal is already met, no
     * second flip, no duplicate alert).
     *
     * <p>Runs inside the caller's transaction (the community resolve's
     * one-unit-of-work rule: the hide and the report's {@code RESOLVED}
     * close commit atomically) and carries the caller's admin
     * authorization — the reviews side re-checks it (defense in depth).
     */
    Optional<UUID> hideAsModerator(UUID reviewId);
}
