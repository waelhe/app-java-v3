package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L37 (realestate systems plan §5 — the featured boost): the boost-window
 * state of one listing, as set by the administrative shading point. The
 * record is the PUT /api/v1/admin/listings/{id}/promotion response — the
 * honest state view after the change (a cleared boost reports
 * {@code null}, the same nullable-Instant contract the entity's
 * {@code promoted_until} column carries).
 *
 * <p>Deliberately NOT part of any public read surface: the boost is
 * visible to the public by POSITION (boosted-first ordering), and the
 * operational state belongs to the admin surface + the Envers revision
 * trail (the plan's criterion 4) — not to the listing detail contract.
 *
 * @param id the listing whose window was set
 * @param promotedUntil the window's end, or {@code null} when cleared
 */
public record ListingPromotion(UUID id, Instant promotedUntil) {
}
