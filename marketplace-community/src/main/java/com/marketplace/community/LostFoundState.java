package com.marketplace.community;

/**
 * JT-20 (#536 discovery waves D1-D4 — the community leg): a
 * {@code LOST_FOUND} neighborhood post's lifecycle state — «مفقودات
 * الحي»'s honest three states. The DB CHECK (V171) pins the SQL side
 * (D-N7's two-sided discipline: this enum mirrors that membership
 * exactly).
 *
 * <ul>
 *   <li>{@code ACTIVE} — the search is on. Stamped by the publish
 *       factory on every {@code LOST_FOUND} post; the state the
 *       discovery rail reads (AC-20: the eligibility-first contract —
 *       only ACTIVE reports surface as active).</li>
 *   <li>{@code RESOLVED} — the poster closed the report (found by other
 *       means, withdrawn, no longer relevant). The owner's own flip,
 *       never the community's.</li>
 *   <li>{@code FOUND} — the lost item/being was recovered. The owner's
 *       own flip, the happy ending the feed's readers wait for.</li>
 * </ul>
 *
 * <p>Non-{@code LOST_FOUND} categories carry NO state at all — the
 * column is NULL for them (V171's own documented shape: a CHECK forcing
 * a value would lie about the five other categories). The state is
 * filled and flipped by the community module alone; a resolved or
 * recovered report never masquerades as an active one anywhere.
 */
public enum LostFoundState {
    ACTIVE,
    RESOLVED,
    FOUND
}
