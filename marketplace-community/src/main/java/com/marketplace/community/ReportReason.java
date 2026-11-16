package com.marketplace.community;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports layer):
 * why a {@link ContentReport} was raised — the plan's own closed
 * vocabulary ({@code SPAM/HARASSMENT/INAPPROPRIATE/OTHER}). The DB-side
 * CHECK on the stored column pins the same membership guard (D-N7 —
 * every enumerated column carries its DB-level guard, the V64 CHECK in
 * the V44 locking shape).
 *
 * <p>{@code OTHER} is the plan's own escape hatch: the reporter's free
 * text is NOT part of this layer's contract (the plan's entity carries
 * no reporter-note field) — the reason enum plus the reported content
 * itself are the moderator's complete evidence, and adding a free-text
 * field is a product decision the plan deliberately did not take.
 */
public enum ReportReason {
    SPAM,
    HARASSMENT,
    INAPPROPRIATE,
    OTHER
}
