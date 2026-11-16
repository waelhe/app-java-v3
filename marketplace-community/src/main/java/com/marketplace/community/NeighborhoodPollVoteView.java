package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The vote write's echo (L52) — the stored facts, nothing else (the
 * EventRsvpView/NeighborhoodGroupMembershipView discipline verbatim).
 * The fresh vote's own row: which poll, which option, which member,
 * when — the card's own state re-renders from the board read it
 * already holds (the vote echo carries no counts and no percentages:
 * the board read's next refresh is the display's own authority).
 */
public record NeighborhoodPollVoteView(
        UUID id,
        UUID pollId,
        UUID optionId,
        UUID memberId,
        Instant createdAt
) {

    /** The write's echo factory: the saved row's own stored facts. */
    static NeighborhoodPollVoteView of(NeighborhoodPollVote vote) {
        return new NeighborhoodPollVoteView(
                vote.getId(),
                vote.getPollId(),
                vote.getOptionId(),
                vote.getMemberId(),
                vote.getCreatedAt());
    }
}
