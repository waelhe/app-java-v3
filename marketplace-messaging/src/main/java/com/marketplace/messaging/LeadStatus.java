package com.marketplace.messaging;

/**
 * L34 (realestate systems plan §5 — lead capture): the lead lifecycle a
 * provider drives from their inbox. The transition set is deliberately
 * one-directional — a read lead cannot become new again, and archiving is
 * terminal (the {@code ListingStatus} house state-machine pattern, sized
 * to this domain: three states, two moves).
 */
public enum LeadStatus {

    NEW,
    READ,
    ARCHIVED;

    private static final java.util.Map<LeadStatus, java.util.Set<LeadStatus>> TRANSITIONS =
            java.util.Map.of(
                    NEW, java.util.Set.of(READ, ARCHIVED),
                    READ, java.util.Set.of(ARCHIVED),
                    ARCHIVED, java.util.Set.of());

    /**
     * The one-way gate: NEW→READ/ARCHIVED, READ→ARCHIVED, ARCHIVED is
     * terminal. Every mutation goes through this check so an illegal move
     * fails loudly in the service instead of silently rewriting history.
     */
    public boolean canTransitionTo(LeadStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }
}
