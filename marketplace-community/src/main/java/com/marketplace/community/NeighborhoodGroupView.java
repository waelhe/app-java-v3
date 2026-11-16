package com.marketplace.community;

import java.util.UUID;

/**
 * The groups board's read model (L51): the stored facts plus the two
 * reader-scoped facts — the NeighborhoodEventView projection
 * discipline verbatim. The group carries no author surface (the
 * registered contract is name/description/members; a creation write is
 * the opened window's next product decision), so there is no
 * {@code mine}-by-authorship fact here — the ONE caller-scoped fact is
 * the membership itself.
 *
 * <p><b>The two facts (the L47/L49/L50 shape verbatim):</b>
 * {@code members} (the LIVE membership count — one grouped aggregate
 * over the page's group ids; the count is earned by real rows, never
 * a seeded display number) and {@code joinedByMe} (the caller's own
 * live membership — the join/leave button renders from the contract
 * alone, no second read).
 */
public record NeighborhoodGroupView(
        UUID id,
        String name,
        String description,
        long members,
        boolean joinedByMe
) {
    /**
     * The board read's factory: the stored facts plus the two
     * reader-scoped facts the grouped count batch and the caller's
     * own-membership read produced.
     */
    static NeighborhoodGroupView of(NeighborhoodGroup group, long members, boolean joinedByMe) {
        return new NeighborhoodGroupView(
                group.getId(),
                group.getName(),
                group.getDescription(),
                members,
                joinedByMe);
    }
}
