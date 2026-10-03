package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L52 — the poll aggregate's own authored shapes (the NeighborhoodGroup
 * entity-test convention verbatim): the three entities carry exactly the
 * registered contract's facts on their columns, every id is a fresh
 * UUID, and the aggregate carries no more than the contract
 * (question/options/votes — the vote counts are READ facts the service
 * derives, never stored columns).
 */
class NeighborhoodPollTest {

    @Test
    void authored_carriesTheThreeAuthoredFactsAndAFreshId() {
        UUID locationId = UUID.randomUUID();

        NeighborhoodPoll poll = NeighborhoodPoll.authored(locationId,
                "ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟", "لجنة تطوير الحي");

        assertThat(poll.getId()).isNotNull();
        assertThat(poll.getLocationId()).isEqualTo(locationId);
        assertThat(poll.getQuestion()).isEqualTo("ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟");
        assertThat(poll.getAuthorLabel()).isEqualTo("لجنة تطوير الحي");
    }

    @Test
    void optionOf_carriesItsPollItsLabelAndItsSubmissionOrdinal() {
        UUID pollId = UUID.randomUUID();

        NeighborhoodPollOption option = NeighborhoodPollOption.optionOf(
                pollId, "المساء — ٥:٠٠ إلى ٨:٣٠", 1);

        assertThat(option.getId()).isNotNull();
        assertThat(option.getPollId()).isEqualTo(pollId);
        assertThat(option.getLabel()).isEqualTo("المساء — ٥:٠٠ إلى ٨:٣٠");
        assertThat(option.getPosition()).isEqualTo(1);
    }

    @Test
    void cast_carriesTheTripleAndAFreshId() {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        NeighborhoodPollVote vote = NeighborhoodPollVote.cast(pollId, optionId, memberId);

        assertThat(vote.getId()).isNotNull();
        assertThat(vote.getPollId()).isEqualTo(pollId);
        assertThat(vote.getOptionId()).isEqualTo(optionId);
        assertThat(vote.getMemberId()).isEqualTo(memberId);
    }

    @Test
    void authored_twiceInOneNeighborhood_yieldsTwoDistinctRows() {
        UUID locationId = UUID.randomUUID();

        NeighborhoodPoll first = NeighborhoodPoll.authored(locationId, "س", "لجنة تطوير الحي");
        NeighborhoodPoll second = NeighborhoodPoll.authored(locationId, "س", "لجنة تطوير الحي");

        // Two same-questioned polls of one hood are two rows — the house
        // has no title uniqueness anywhere (posts, events, market items,
        // groups), and the board's complete sort key keeps them
        // deterministically apart.
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}
