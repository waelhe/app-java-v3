package com.marketplace.community;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports layer):
 * the report queue's own state machine — {@code OPEN} from creation,
 * {@code RESOLVED} when the moderator's action was DISMISS's opposite
 * ({@code HIDE_CONTENT}: the content was hidden/soft-deleted in the same
 * transaction), {@code DISMISSED} when the moderator judged no action
 * needed.
 *
 * <p>The transitions are the service's alone and one-directional: a
 * report leaves {@code OPEN} exactly once, through the administrative
 * resolve command — a second resolve on a closed report answers 409 (the
 * state-machine honesty the house {@code ConflictException} carries; a
 * closed report's history is closed history, the Envers trail keeps
 * every flip). The DB CHECK pins the SQL side (D-N7, V64 in the V44
 * locking shape).
 */
public enum ReportStatus {
    OPEN,
    RESOLVED,
    DISMISSED
}
