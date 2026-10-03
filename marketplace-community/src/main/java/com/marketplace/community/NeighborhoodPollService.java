package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The neighborhood polls surface (L52 — the Nextdoor-2026 completeness
 * wave, gap #7, the registered contract's own real write: «صوت واحد لكل
 * عضو»). One read, three commands — all the house precedents, measured:
 *
 * <p><b>The read gate (G-N3's default, verbatim from the feed, the
 * events board, the market board and the groups board):</b> the polls
 * are for ACTIVE members of the neighborhood — an authenticated caller
 * with no active membership answers the explicit 403, never an empty
 * 200 that pretends the board exists. ANY verification state reads
 * (D-N3: REJECTED blocks community writes, not the board).
 *
 * <p><b>The create gate order (L41's own discipline, verbatim from the
 * post publish and the event organize):</b> the location is resolved
 * through {@link GeoLookupPort} FIRST — an unknown node is the port's
 * own 404 — then the level-3 requirement answers 400 BEFORE any write,
 * then the active-membership match (403), and only then the poll's own
 * type-level gate: the option-set cardinality (400 — the registered
 * contract's own «ONE question, 2–5 options»; the label bounds ride the
 * bean-side validation like the market title's). The poll and its full
 * option set insert as ONE authored unit — a poll without its options
 * is never a valid intermediate state.
 *
 * <p><b>The vote gate order (the L47 comment/reaction / L49 RSVP / L51
 * join order verbatim):</b> the poll gate first (an unknown or retired
 * poll answers the honest 404 — a retired poll's options and votes are
 * absent exactly as the poll itself is), then the option's own gates
 * (the honest 404 for an unknown option, then the 400 when the option
 * belongs to a DIFFERENT poll — a malformed reference for THIS poll,
 * the parse-then-400 discipline's own id-shaped twin), then the
 * active-membership gate in the poll's OWN {@code locationId} (403 — a
 * vote is a community commitment like a comment or a seat; a REJECTED
 * verification cannot write, and a member of a DIFFERENT neighborhood
 * is the same 403), then the one-vote check (409 — «صوت واحد لكل عضو»,
 * the registered contract's own words; the V64/V73/V83/V91 partial
 * unique index is the backstop — the reactions' no-lock model, an
 * id-pair write with no capacity to count). Only then the insert.
 *
 * <p><b>The withdraw gate (the /me owner-delete convention, the
 * groups-leave / un-RSVP stance verbatim):</b> the honest poll 404,
 * then the caller's OWN live vote — an owner-scoped removal, exactly
 * the shape the favorites/follows/saved-searches deletes prove. The
 * vote's neighborhood gate does NOT ride the withdraw: a member who
 * has since LEFT or SWITCHED the neighborhood would otherwise be
 * locked out of his own stale vote forever. The withdrawn
 * (soft-deleted) vote frees the member to vote again — the
 * seat-frees-itself shape every id-pair toggle since the reactions has
 * ridden.
 *
 * <p><b>The board's reader-scoped facts (the L47/L49/L50/L51 grouped
 * shape verbatim):</b> the options come from ONE IN read over the
 * page's poll ids (sorted by the author's own (position, id) pair —
 * D-N5's complete key at the option level), the LIVE per-option vote
 * counts from ONE grouped aggregate over the page's OPTION ids —
 * earned by real rows, never a seeded display number — and
 * {@code votedByMe} (the caller's own chosen option id) from ONE IN
 * read over the poll ids. The empty page short-circuits and costs
 * neither.
 *
 * <p><b>Deterministic pagination (D-N5):</b> the board read forces the
 * complete sort key — {@code created_at DESC, id DESC} — the market
 * board's own newest-first discipline (the featured zone carries the
 * LATEST poll; two polls authored in the same second never shake a
 * page boundary).
 */
@Service
@Transactional
public class NeighborhoodPollService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood. The same constant the membership, post, event and
     * market services gate on — one vocabulary, the port's int.
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    /** The board's complete sort key (D-N5) — newest first, id breaking ties. */
    private static final Sort BOARD_SORT =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    /** The option rows' display order — the author's own (position, id) pair. */
    private static final Comparator<NeighborhoodPollOption> OPTION_ORDER =
            Comparator.comparingInt(NeighborhoodPollOption::getPosition)
                    .thenComparing(NeighborhoodPollOption::getId);

    /** The registered contract's own option-set bounds: «ONE question, 2–5 options». */
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 5;

    private final NeighborhoodPollRepository pollRepository;
    private final NeighborhoodPollOptionRepository optionRepository;
    private final NeighborhoodPollVoteRepository voteRepository;
    private final NeighborhoodMembershipRepository membershipRepository;
    private final GeoLookupPort geoLookupPort;

    public NeighborhoodPollService(NeighborhoodPollRepository pollRepository,
                                   NeighborhoodPollOptionRepository optionRepository,
                                   NeighborhoodPollVoteRepository voteRepository,
                                   NeighborhoodMembershipRepository membershipRepository,
                                   GeoLookupPort geoLookupPort) {
        this.pollRepository = pollRepository;
        this.optionRepository = optionRepository;
        this.voteRepository = voteRepository;
        this.membershipRepository = membershipRepository;
        this.geoLookupPort = geoLookupPort;
    }

    /**
     * The board — the caller's OWN neighborhood's polls, on the complete
     * sort key, every row carrying the full authored option set (each
     * with its LIVE vote count) and the caller's own chosen option id.
     * No membership ⇒ the explicit 403 (G-N3's default) — there is no
     * location parameter to read anyone else's board: the membership
     * IS the scope. ANY verification state reads (D-N3: REJECTED blocks
     * community writes, never the board).
     */
    @Transactional(readOnly = true)
    public Page<NeighborhoodPollView> getBoard(UUID callerId, Pageable pageable) {
        UUID locationId = requireMembership(callerId,
                "Join a neighborhood before reading its polls board (PUT /api/v1/me/neighborhood)")
                .getLocationId();
        Pageable boardPageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), BOARD_SORT);
        Page<NeighborhoodPoll> page = pollRepository.findByLocationId(locationId, boardPageable);
        List<NeighborhoodPoll> polls = page.getContent();
        if (polls.isEmpty()) {
            return page.map(poll -> NeighborhoodPollView.of(poll, List.of(), null));
        }
        // The board read carries the reader-scoped facts — the options
        // batch (one IN read over the page's poll ids), the grouped LIVE
        // count per option (one aggregate over the page's option ids)
        // and the caller's own live votes (one IN read over the poll
        // ids) — the L47/L49/L50/L51 pattern verbatim: three reads after
        // the page, never a read per row.
        List<UUID> pollIds = polls.stream().map(NeighborhoodPoll::getId).toList();
        Map<UUID, List<NeighborhoodPollOption>> optionsByPoll = optionRepository
                .findByPollIdIn(pollIds).stream()
                .collect(Collectors.groupingBy(NeighborhoodPollOption::getPollId));
        List<UUID> optionIds = optionsByPoll.values().stream()
                .flatMap(List::stream).map(NeighborhoodPollOption::getId).toList();
        Map<UUID, Long> counts = voteCounts(optionIds);
        Map<UUID, UUID> myChoices = myVotes(callerId, pollIds);
        return page.map(poll -> {
            List<NeighborhoodPollOptionView> options = optionsByPoll
                    .getOrDefault(poll.getId(), List.of()).stream()
                    .sorted(OPTION_ORDER)
                    .map(option -> NeighborhoodPollOptionView.of(
                            option, counts.getOrDefault(option.getId(), 0L)))
                    .toList();
            return NeighborhoodPollView.of(poll, options, myChoices.get(poll.getId()));
        });
    }

    /**
     * Author a poll — the caller writes into their own active
     * neighborhood. The gate order is the post publish / event organize
     * verbatim: port resolve (404) → level-3 (400) → active writable
     * membership in exactly that location (403) → the poll's own type
     * gate (the option-set cardinality 400 — the registered contract's
     * own «ONE question, 2–5 options»; the label bounds ride the
     * bean-side validation) → insert. The poll and its full option set
     * insert as ONE authored unit in ONE transaction — the author's own
     * submission order IS the options' stored position.
     */
    @Observed(name = "community.poll.create")
    public NeighborhoodPollView create(UUID authorId, UUID locationId, String question,
                                       String authorLabel, List<String> optionLabels) {
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(locationId);
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
        requireWritableMembershipIn(authorId, locationId,
                "Join a neighborhood before authoring polls (PUT /api/v1/me/neighborhood)",
                "Polls go to your own neighborhood — this location is not it");
        if (optionLabels == null || optionLabels.size() < MIN_OPTIONS
                || optionLabels.size() > MAX_OPTIONS) {
            throw new BadRequestException(
                    "A poll carries one question and 2 to 5 options — got "
                            + (optionLabels == null ? 0 : optionLabels.size()));
        }
        NeighborhoodPoll saved = pollRepository.save(
                NeighborhoodPoll.authored(locationId, question, authorLabel));
        List<NeighborhoodPollOption> options = new java.util.ArrayList<>(optionLabels.size());
        for (int position = 0; position < optionLabels.size(); position++) {
            options.add(optionRepository.save(NeighborhoodPollOption.optionOf(
                    saved.getId(), optionLabels.get(position), position)));
        }
        return NeighborhoodPollView.of(saved, options.stream()
                .map(option -> NeighborhoodPollOptionView.of(option, 0L)).toList(), null);
    }

    /**
     * Vote — one live vote per member per poll. The gate order is the
     * L47 reaction / L49 RSVP / L51 join order verbatim: the poll's
     * honest 404 (a retired poll's options and votes are absent exactly
     * as the poll itself is), then the option's own gates (the honest
     * 404 for an unknown option, the 400 when the option belongs to a
     * different poll), then the active-membership gate in the poll's
     * OWN {@code locationId} (403 — a REJECTED verification cannot
     * vote, and a member of a different neighborhood is the same 403),
     * then the one-vote check (409 — «صوت واحد لكل عضو»; the V100
     * partial unique index is the backstop). Only then the insert.
     */
    @Observed(name = "community.poll.vote")
    public NeighborhoodPollVoteView vote(UUID memberId, UUID pollId, UUID optionId) {
        NeighborhoodPoll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll", pollId));
        NeighborhoodPollOption option = optionRepository.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll option", optionId));
        if (!option.getPollId().equals(poll.getId())) {
            throw new BadRequestException(
                    "The option does not belong to this poll — a vote carries one of the poll's own options");
        }
        requireWritableMembershipIn(memberId, poll.getLocationId(),
                "Join a neighborhood before voting in its polls (PUT /api/v1/me/neighborhood)",
                "Votes go to your own neighborhood's polls — this poll is not yours to vote");
        if (voteRepository.findByPollIdAndMemberId(pollId, memberId).isPresent()) {
            throw new ConflictException("One vote per member per poll — withdraw it before voting again");
        }
        NeighborhoodPollVote saved = voteRepository.save(
                NeighborhoodPollVote.cast(pollId, optionId, memberId));
        return NeighborhoodPollVoteView.of(saved);
    }

    /**
     * Withdraw my vote — remove the caller's own LIVE vote. The poll's
     * existence gate answers the honest 404 (a retired poll's votes are
     * absent exactly as the poll itself is), and the removal is
     * owner-scoped (the /me owner-delete convention the groups-leave
     * measured): a member who has left or switched the neighborhood can
     * still take his stale vote with him — the vote's neighborhood gate
     * never rides the withdraw. The withdraw is the house soft delete —
     * the row stays (b-5's retention, the Envers trail keeps the
     * revision) and the member is free to vote again (the V100 partial
     * unique index admits exactly that).
     */
    @Observed(name = "community.poll.withdraw")
    public void withdraw(UUID memberId, UUID pollId) {
        pollRepository.findById(pollId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll", pollId));
        NeighborhoodPollVote vote = voteRepository.findByPollIdAndMemberId(pollId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll vote", pollId));
        voteRepository.delete(vote);
    }

    /**
     * L52: the grouped LIVE vote count per option over the page's
     * option ids — the empty option set short-circuits to the empty map
     * (a closed board costs no aggregate).
     */
    private Map<UUID, Long> voteCounts(List<UUID> optionIds) {
        if (optionIds.isEmpty()) {
            return Map.of();
        }
        return voteRepository.countByOptionIdIn(optionIds).stream()
                .collect(Collectors.toMap(
                        NeighborhoodPollVoteRepository.PollVoteCount::getOptionId,
                        NeighborhoodPollVoteRepository.PollVoteCount::getTotalCount));
    }

    /**
     * L52: the caller's own live votes across the page's polls — the
     * chosen option id per poll (the votedByMe projection's one
     * per-reader fact).
     */
    private Map<UUID, UUID> myVotes(UUID callerId, List<UUID> pollIds) {
        return voteRepository.findByMemberIdAndPollIdIn(callerId, pollIds).stream()
                .collect(Collectors.toMap(
                        NeighborhoodPollVote::getPollId,
                        NeighborhoodPollVote::getOptionId));
    }

    /**
     * The caller's membership — ANY verification state reads (D-N3:
     * REJECTED blocks community writes, never the board). Absent
     * membership ⇒ the explicit 403.
     */
    private NeighborhoodMembership requireMembership(UUID callerId, String noMembershipMessage) {
        return membershipRepository.findByUserId(callerId)
                .orElseThrow(() -> new AccessDeniedException(noMembershipMessage));
    }

    /**
     * The caller's membership WITH the community-write right — a
     * REJECTED claim answers the explicit 403 (G-N3; the #461 round:
     * the write gate, not the shared existence gate, carries this
     * check).
     */
    private NeighborhoodMembership requireWritableMembership(UUID callerId, String noMembershipMessage) {
        NeighborhoodMembership membership = requireMembership(callerId, noMembershipMessage);
        if (!membership.mayUseCommunityWrites()) {
            throw new AccessDeniedException("Rejected neighborhood verification cannot publish, comment, or recommend");
        }
        return membership;
    }

    /**
     * The membership-in-location WRITE gate: absent membership ⇒ 403
     * with the join hint; a membership in a DIFFERENT neighborhood ⇒
     * 403 with the scope fact — both checks land before any write.
     */
    private NeighborhoodMembership requireWritableMembershipIn(UUID callerId, UUID locationId,
                                                               String noMembershipMessage,
                                                               String wrongLocationMessage) {
        NeighborhoodMembership membership =
                requireWritableMembership(callerId, noMembershipMessage);
        if (!membership.getLocationId().equals(locationId)) {
            throw new AccessDeniedException(wrongLocationMessage);
        }
        return membership;
    }
}
