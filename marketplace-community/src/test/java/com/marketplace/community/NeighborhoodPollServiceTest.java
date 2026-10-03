package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L52 — the polls board service's gate orders, unit-pinned (the plan's
 * acceptance criteria; the module integration test proves the same
 * against the real schema):
 *
 * <ul>
 *   <li>the create gate order (the L41 publish order verbatim): the
 *       port's own 404 → the level-3 requirement 400 → the active
 *       writable membership in exactly that location (403 — absent, a
 *       different neighborhood, or a REJECTED verification) → the
 *       option-set cardinality 400 (the registered contract's own
 *       «2 to 5») → the ONE-unit insert (poll + its full option
 *       set);</li>
 *   <li>the vote gate order (the L47/L49/L51 order verbatim): the
 *       poll's honest 404 → the option's own gates (404 unknown, 400
 *       when it belongs to a different poll) → the active writable
 *       membership in the poll's OWN neighborhood (403) → the
 *       one-vote check (409) → insert;</li>
 *   <li>the withdraw gate (the /me owner-delete convention): the
 *       poll's honest 404 → the honest 404 for a member with no live
 *       vote → the house soft delete; the vote's neighborhood gate
 *       NEVER rides the withdraw;</li>
 *   <li>the board read gate: no active membership ⇒ 403 (G-N3's
 *       default); the board carries the full authored option set
 *       (each with its LIVE count) + votedByMe (the caller's own
 *       chosen option id); the empty page costs none of the three
 *       batch reads.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodPollServiceTest {

    @Mock
    private NeighborhoodPollRepository pollRepository;

    @Mock
    private NeighborhoodPollOptionRepository optionRepository;

    @Mock
    private NeighborhoodPollVoteRepository voteRepository;

    @Mock
    private NeighborhoodMembershipRepository membershipRepository;

    @Mock
    private GeoLookupPort geoLookupPort;

    private NeighborhoodPollService service;

    @BeforeEach
    void setUp() {
        service = new NeighborhoodPollService(pollRepository, optionRepository,
                voteRepository, membershipRepository, geoLookupPort);
    }

    private final UUID callerId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final UUID pollId = UUID.randomUUID();
    private final UUID optionId = UUID.randomUUID();

    private GeoLookupPort.GeoNode node(int level) {
        return new GeoLookupPort.GeoNode(locationId, null, level, "حي", null, "node");
    }

    private NeighborhoodPoll pollIn(UUID location) {
        return NeighborhoodPoll.authored(location,
                "ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟", "لجنة تطوير الحي");
    }

    private NeighborhoodPollOption optionOf(UUID poll) {
        return NeighborhoodPollOption.optionOf(poll, "المساء — ٥:٠٠ إلى ٨:٣٠", 1);
    }

    private NeighborhoodMembership membershipOf(UUID user, UUID location) {
        return NeighborhoodMembership.join(user, location,
                java.time.Clock.systemUTC());
    }

    /** The full refusal path: join → request → reject. */
    private NeighborhoodMembership membershipRejected(UUID user, UUID location) {
        NeighborhoodMembership membership = membershipOf(user, location);
        membership.requestVerification();
        membership.rejectVerification();
        return membership;
    }

    // ---------- create: the gate order ----------

    @Test
    void create_unknownLocation_isThePortsOwn404() {
        when(geoLookupPort.getLocation(locationId))
                .thenThrow(new ResourceNotFoundException("Location", locationId));

        assertThatThrownBy(() -> service.create(callerId, locationId,
                "س", "لجنة تطوير الحي", List.of("أ", "ب")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(pollRepository, never()).save(any());
    }

    @Test
    void create_nonLevel3Node_is400BeforeAnyWrite() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(2));

        assertThatThrownBy(() -> service.create(callerId, locationId,
                "س", "لجنة تطوير الحي", List.of("أ", "ب")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verify(pollRepository, never()).save(any());
    }

    @Test
    void create_noMembership_is403() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(callerId, locationId,
                "س", "لجنة تطوير الحي", List.of("أ", "ب")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(pollRepository, never()).save(any());
    }

    @Test
    void create_membershipInAnotherNeighborhood_is403() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.create(callerId, locationId,
                "س", "لجنة تطوير الحي", List.of("أ", "ب")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("your own neighborhood");
        verify(pollRepository, never()).save(any());
    }

    @Test
    void create_rejectedVerification_is403() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipRejected(callerId, locationId)));

        assertThatThrownBy(() -> service.create(callerId, locationId,
                "س", "لجنة تطوير الحي", List.of("أ", "ب")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Rejected neighborhood verification");
        verify(pollRepository, never()).save(any());
    }

    @Test
    void create_oneOption_is400WithTheContractsOwnWords() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));

        assertThatThrownBy(() -> service.create(callerId, locationId,
                "س", "لجنة تطوير الحي", List.of("أ")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2 to 5 options");
        verify(pollRepository, never()).save(any());
    }

    @Test
    void create_sixOptions_is400WithTheContractsOwnWords() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));

        assertThatThrownBy(() -> service.create(callerId, locationId,
                "س", "لجنة تطوير الحي", List.of("أ", "ب", "ج", "د", "هـ", "و")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2 to 5 options");
        verify(pollRepository, never()).save(any());
    }

    @Test
    void create_happyPath_authorsThePollAndItsFullOptionSetAsOneUnit() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        when(pollRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(optionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NeighborhoodPollView view = service.create(callerId, locationId,
                "ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟",
                "لجنة تطوير الحي",
                List.of("الفجر — ٥:٣٠ إلى ٨:٠٠", "المساء — ٥:٠٠ إلى ٨:٣٠", "كلا الفترتين"));

        assertThat(view.question()).isEqualTo("ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟");
        assertThat(view.author()).isEqualTo("لجنة تطوير الحي");
        assertThat(view.options()).hasSize(3);
        assertThat(view.options()).extracting(NeighborhoodPollOptionView::position)
                .containsExactly(0, 1, 2);
        assertThat(view.votedByMe()).isNull();
        // The ONE-unit insert: the poll once, its FULL option set with it.
        verify(pollRepository).save(any());
        verify(optionRepository, org.mockito.Mockito.times(3)).save(any());
    }

    // ---------- vote: the gate order ----------

    /** A poll and its own option, ids coherent — the vote's reference gates read the entity's own id. */
    private record PollWithOption(NeighborhoodPoll poll, NeighborhoodPollOption option) {
        UUID pollId() { return poll.getId(); }
        UUID optionId() { return option.getId(); }
    }

    private PollWithOption pollWithOptionIn(UUID location) {
        NeighborhoodPoll poll = pollIn(location);
        return new PollWithOption(poll, optionOf(poll.getId()));
    }

    @Test
    void vote_unknownPoll_isTheHonest404() {
        when(pollRepository.findById(pollId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.vote(callerId, pollId, optionId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(voteRepository, never()).save(any());
    }

    @Test
    void vote_unknownOption_isTheHonest404() {
        when(pollRepository.findById(pollId)).thenReturn(Optional.of(pollIn(locationId)));
        when(optionRepository.findById(optionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.vote(callerId, pollId, optionId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(voteRepository, never()).save(any());
    }

    @Test
    void vote_optionOfAnotherPoll_is400BeforeAnyMembershipRead() {
        NeighborhoodPoll poll = pollIn(locationId);
        // An option of a DIFFERENT poll — the malformed reference for THIS poll.
        when(pollRepository.findById(poll.getId())).thenReturn(Optional.of(poll));
        when(optionRepository.findById(optionId)).thenReturn(Optional.of(optionOf(UUID.randomUUID())));

        assertThatThrownBy(() -> service.vote(callerId, poll.getId(), optionId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not belong to this poll");
        verify(membershipRepository, never()).findByUserId(any());
        verify(voteRepository, never()).save(any());
    }

    @Test
    void vote_noMembership_is403() {
        PollWithOption pair = pollWithOptionIn(locationId);
        when(pollRepository.findById(pair.pollId())).thenReturn(Optional.of(pair.poll()));
        when(optionRepository.findById(pair.optionId())).thenReturn(Optional.of(pair.option()));
        when(membershipRepository.findByUserId(callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.vote(callerId, pair.pollId(), pair.optionId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(voteRepository, never()).save(any());
    }

    @Test
    void vote_membershipInAnotherNeighborhood_is403() {
        PollWithOption pair = pollWithOptionIn(locationId);
        when(pollRepository.findById(pair.pollId())).thenReturn(Optional.of(pair.poll()));
        when(optionRepository.findById(pair.optionId())).thenReturn(Optional.of(pair.option()));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.vote(callerId, pair.pollId(), pair.optionId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("your own neighborhood");
        verify(voteRepository, never()).save(any());
    }

    @Test
    void vote_rejectedVerification_is403() {
        PollWithOption pair = pollWithOptionIn(locationId);
        when(pollRepository.findById(pair.pollId())).thenReturn(Optional.of(pair.poll()));
        when(optionRepository.findById(pair.optionId())).thenReturn(Optional.of(pair.option()));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipRejected(callerId, locationId)));

        assertThatThrownBy(() -> service.vote(callerId, pair.pollId(), pair.optionId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Rejected neighborhood verification");
        verify(voteRepository, never()).save(any());
    }

    @Test
    void vote_alreadyVoted_is409WithTheContractsOwnWords() {
        PollWithOption pair = pollWithOptionIn(locationId);
        when(pollRepository.findById(pair.pollId())).thenReturn(Optional.of(pair.poll()));
        when(optionRepository.findById(pair.optionId())).thenReturn(Optional.of(pair.option()));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        when(voteRepository.findByPollIdAndMemberId(pair.pollId(), callerId))
                .thenReturn(Optional.of(NeighborhoodPollVote.cast(pair.pollId(), pair.optionId(), callerId)));

        assertThatThrownBy(() -> service.vote(callerId, pair.pollId(), pair.optionId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("One vote per member per poll");
        verify(voteRepository, never()).save(any());
    }

    @Test
    void vote_happyPath_castsTheSingleLiveVote() {
        PollWithOption pair = pollWithOptionIn(locationId);
        when(pollRepository.findById(pair.pollId())).thenReturn(Optional.of(pair.poll()));
        when(optionRepository.findById(pair.optionId())).thenReturn(Optional.of(pair.option()));
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        when(voteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NeighborhoodPollVoteView echo = service.vote(callerId, pair.pollId(), pair.optionId());

        assertThat(echo.pollId()).isEqualTo(pair.pollId());
        assertThat(echo.optionId()).isEqualTo(pair.optionId());
        assertThat(echo.memberId()).isEqualTo(callerId);
    }

    // ---------- withdraw: the owner-delete convention ----------

    @Test
    void withdraw_unknownPoll_isTheHonest404() {
        when(pollRepository.findById(pollId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(callerId, pollId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(voteRepository, never()).delete(any());
    }

    @Test
    void withdraw_noLiveVote_isTheHonest404() {
        when(pollRepository.findById(pollId)).thenReturn(Optional.of(pollIn(locationId)));
        when(voteRepository.findByPollIdAndMemberId(pollId, callerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(callerId, pollId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(voteRepository, never()).delete(any());
    }

    @Test
    void withdraw_formerNeighbor_takesHisStaleVoteWithHim() {
        // The poll lives in a hood the caller has since LEFT — the
        // withdraw never gates on the membership (the groups-leave
        // measured correction verbatim).
        when(pollRepository.findById(pollId)).thenReturn(Optional.of(pollIn(UUID.randomUUID())));
        NeighborhoodPollVote vote = NeighborhoodPollVote.cast(pollId, optionId, callerId);
        when(voteRepository.findByPollIdAndMemberId(pollId, callerId))
                .thenReturn(Optional.of(vote));

        service.withdraw(callerId, pollId);

        verify(voteRepository).delete(vote);
        verify(membershipRepository, never()).findByUserId(any());
    }

    // ---------- getBoard ----------

    @Test
    void getBoard_noMembership_is403() {
        when(membershipRepository.findByUserId(callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBoard(callerId, Pageable.unpaged()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
    }

    @Test
    void getBoard_carriesTheOptionSetWithLiveCountsAndMyChoicePerRow() {
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        NeighborhoodPoll one = pollIn(locationId);
        NeighborhoodPollOption optionOne = NeighborhoodPollOption.optionOf(one.getId(), "الفجر — ٥:٣٠ إلى ٨:٠٠", 0);
        NeighborhoodPollOption optionTwo = NeighborhoodPollOption.optionOf(one.getId(), "المساء — ٥:٠٠ إلى ٨:٣٠", 1);
        NeighborhoodPollOption optionThree = NeighborhoodPollOption.optionOf(one.getId(), "كلا الفترتين", 2);
        when(pollRepository.findByLocationId(org.mockito.ArgumentMatchers.eq(locationId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(one)));
        when(optionRepository.findByPollIdIn(List.of(one.getId())))
                .thenReturn(List.of(optionThree, optionOne, optionTwo));
        when(voteRepository.countByOptionIdIn(any()))
                .thenReturn(List.of(
                        new VoteCountRow(optionOne.getId(), 1L),
                        new VoteCountRow(optionTwo.getId(), 2L)));
        when(voteRepository.findByMemberIdAndPollIdIn(callerId, List.of(one.getId())))
                .thenReturn(List.of(NeighborhoodPollVote.cast(one.getId(), optionTwo.getId(), callerId)));

        Page<NeighborhoodPollView> board = service.getBoard(callerId, PageRequest.of(0, 20));

        assertThat(board.getContent()).hasSize(1);
        NeighborhoodPollView row = board.getContent().get(0);
        // The options render in the AUTHOR'S OWN order (position), not
        // the repository's incidental order.
        assertThat(row.options()).extracting(NeighborhoodPollOptionView::position)
                .containsExactly(0, 1, 2);
        assertThat(row.options()).extracting(NeighborhoodPollOptionView::votes)
                .containsExactly(1L, 2L, 0L);
        // votedByMe is the CALLER'S OWN chosen option id — the richer
        // twin of rsvpedByMe (the vote HAS a choice).
        assertThat(row.votedByMe()).isEqualTo(optionTwo.getId());
    }

    @Test
    void getBoard_sortsNewestFirstOnTheCompleteKey() {
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        when(pollRepository.findByLocationId(org.mockito.ArgumentMatchers.eq(locationId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getBoard(callerId, PageRequest.of(0, 20));

        // The complete sort key rides the caller's Pageable (D-N5) — the
        // board's newest-first order: created_at DESC, id DESC.
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(pollRepository).findByLocationId(org.mockito.ArgumentMatchers.eq(locationId), captor.capture());
        Pageable captured = captor.getValue();
        assertThat(captured.getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
        assertThat(captured.getSort().getOrderFor("id").getDirection())
                .isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
    }

    @Test
    void getBoard_emptyPage_costsNeitherBatchRead() {
        when(membershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        when(pollRepository.findByLocationId(org.mockito.ArgumentMatchers.eq(locationId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<NeighborhoodPollView> board = service.getBoard(callerId, PageRequest.of(0, 20));

        assertThat(board.getContent()).isEmpty();
        verify(optionRepository, never()).findByPollIdIn(any());
        verify(voteRepository, never()).countByOptionIdIn(any());
        verify(voteRepository, never()).findByMemberIdAndPollIdIn(any(), any());
    }

    /** The grouped count's own projection row (the events test's RsvpCountRow shape). */
    private record VoteCountRow(UUID optionId, long totalCount)
            implements NeighborhoodPollVoteRepository.PollVoteCount {
        @Override
        public UUID getOptionId() {
            return optionId;
        }

        @Override
        public long getTotalCount() {
            return totalCount;
        }
    }
}
