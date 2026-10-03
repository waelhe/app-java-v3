package com.marketplace.community;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The polls board's read model (L52): the stored facts plus the TWO
 * reader-scoped facts — the NeighborhoodEventView/NeighborhoodGroupView
 * projection discipline verbatim. The registered contract
 * (docs/product-charter.md §7/7-4 — question/options/votes) renders
 * from this one read alone:
 *
 * <ul>
 *  <li>{@code options} — the full authored option set in the author's
 *      own order (position, id), every row carrying the LIVE vote
 *      count (one grouped aggregate over the page's option ids — the
 *      percentage bars' own denominators);</li>
 *  <li>{@code votedByMe} — the caller's own live vote's OPTION id (or
 *      null when the caller has not voted): the ONE caller-scoped
 *      fact. The card renders its «mine» mark (the chosen option) and
 *      the honest not-yet-voted state from this field alone, no
 *      second read (the rsvpedByMe discipline's richer twin — the
 *      vote HAS a choice, so the projection carries the choice).</li>
 * </ul>
 */
public record NeighborhoodPollView(
        UUID id,
        String question,
        String author,
        List<NeighborhoodPollOptionView> options,
        UUID votedByMe,
        Instant createdAt
) {

    /**
     * The board read's factory: the stored facts plus the composed
     * option set (each with its live count) and the caller's own
     * vote's option id the batches produced.
     */
    static NeighborhoodPollView of(NeighborhoodPoll poll,
                                   List<NeighborhoodPollOptionView> options,
                                   UUID votedByMe) {
        return new NeighborhoodPollView(
                poll.getId(),
                poll.getQuestion(),
                poll.getAuthorLabel(),
                options,
                votedByMe,
                poll.getCreatedAt());
    }
}
