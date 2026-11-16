package com.marketplace.community;

import java.util.UUID;

/**
 * The poll option's read model (L52): the registered contract's own
 * option shape — the label plus the LIVE vote count (the design's
 * percentage bar: the count is earned by real vote rows, one grouped
 * aggregate over the board page's option ids, never a seeded display
 * number) — plus the option's own id and position so the card renders
 * the author's own order and marks the caller's own choice without a
 * second read.
 */
public record NeighborhoodPollOptionView(
        UUID id,
        String label,
        int position,
        long votes
) {

    /** The board read's factory: the stored facts plus the LIVE count the grouped batch produced. */
    static NeighborhoodPollOptionView of(NeighborhoodPollOption option, long votes) {
        return new NeighborhoodPollOptionView(
                option.getId(),
                option.getLabel(),
                option.getPosition(),
                votes);
    }
}
