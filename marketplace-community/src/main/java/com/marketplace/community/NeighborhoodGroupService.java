package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The neighborhood groups board surface (L51 — the Nextdoor-2026
 * completeness wave, gap #6). One read, two commands — all the house
 * precedents, measured:
 *
 * <p><b>The read gate (G-N3's default, verbatim from the feed, the
 * events board and the market board):</b> the groups are for ACTIVE
 * members of the neighborhood — an authenticated caller with no active
 * membership answers the explicit 403, never an empty 200 that
 * pretends the board exists. ANY verification state reads (D-N3:
 * REJECTED blocks community writes, not the board).
 *
 * <p><b>The join gate order (the L47 comment/reaction and L49 RSVP
 * order verbatim):</b> the group gate first (an unknown or retired
 * group answers the honest 404 — a retired group's memberships are
 * absent exactly as the group itself is), then the active-membership
 * gate in the group's OWN {@code locationId} (403 — a membership is a
 * community commitment like a comment or a seat; a REJECTED
 * verification cannot write, and a member of a DIFFERENT neighborhood
 * is the same 403), then the one-membership check (409 — «عضوية واحدة
 * لكل جار», the V64/V73/V83 precedent; the V91 partial unique index is
 * the backstop — the reactions' own no-lock model, an id-pair write
 * with no capacity to count). There is no geo-port resolve here BY
 * DESIGN: the group's own {@code locationId} IS the resolved target
 * (there is no group-creation write this wave — the seed authors the
 * clubs against the geo seed's closed level-3 skeleton — so the join
 * reads the stored fact instead of re-resolving it; the L41
 * level-discipline rides the authoring gate).
 *
 * <p><b>The leave gate (the un-RSVP's own shape verbatim):</b> the
 * same gates as the join (the honest group 404, then the membership
 * 403), and a member with no live membership on the group answers the
 * honest 404 (there is nothing to leave). The leave is the house soft
 * delete — the row stays (b-5's retention, the Envers trail keeps the
 * revision) and the seat is free for a fresh join (the V91 partial
 * unique index admits exactly that).
 *
 * <p><b>The board's two reader-scoped facts (the L47/L49/L50 grouped
 * shape verbatim):</b> the LIVE member count comes from ONE grouped
 * aggregate over the page's group ids — earned by real rows, never a
 * seeded display number («بعددها الحقيقي») — and {@code joinedByMe}
 * from ONE IN read over the same ids. The empty page short-circuits
 * and costs neither.
 *
 * <p><b>Deterministic pagination (D-N5):</b> the board read forces the
 * complete sort key — {@code created_at ASC, id ASC} — so two clubs
 * seeded in the same second never shake a page boundary (the groups
 * board is the hood's HISTORICAL order: the oldest club first, the
 * seed's own insertion order IS the design's display order — unlike
 * the market board's newest-first or the events board's time key).
 */
@Service
@Transactional
public class NeighborhoodGroupService {

    /** The board's complete sort key (D-N5) — the hood's historical order, id breaking ties. */
    private static final Sort BOARD_SORT =
            Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final NeighborhoodGroupRepository groupRepository;
    private final NeighborhoodGroupMembershipRepository membershipRepository;
    private final NeighborhoodMembershipRepository hoodMembershipRepository;

    public NeighborhoodGroupService(NeighborhoodGroupRepository groupRepository,
                                    NeighborhoodGroupMembershipRepository membershipRepository,
                                    NeighborhoodMembershipRepository hoodMembershipRepository) {
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.hoodMembershipRepository = hoodMembershipRepository;
    }

    /**
     * The board — the caller's OWN neighborhood's clubs, on the
     * complete sort key, every row carrying the two reader-scoped
     * facts (the LIVE member count and the caller's own membership).
     * No membership ⇒ the explicit 403 (G-N3's default) — there is no
     * location parameter to read anyone else's board: the membership
     * IS the scope. ANY verification state reads (D-N3: REJECTED
     * blocks community writes, not the board).
     */
    @Transactional(readOnly = true)
    public Page<NeighborhoodGroupView> getBoard(UUID callerId, Pageable pageable) {
        UUID locationId = requireMembership(callerId,
                "Join a neighborhood before reading its groups board (PUT /api/v1/me/neighborhood)")
                .getLocationId();
        Pageable boardPageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), BOARD_SORT);
        Page<NeighborhoodGroup> page = groupRepository.findByLocationId(locationId, boardPageable);
        // The board read carries the two reader-scoped facts — the
        // grouped LIVE count per group and the caller's own membership
        // (the L47/L49 pattern verbatim: one grouped aggregate + one IN
        // read over the page's ids; the empty page short-circuits below
        // and costs neither).
        List<NeighborhoodGroup> groups = page.getContent();
        Map<UUID, Long> counts = memberCounts(groups);
        Set<UUID> mine = myGroups(callerId, groups);
        return page.map(group -> NeighborhoodGroupView.of(
                group,
                counts.getOrDefault(group.getId(), 0L),
                mine.contains(group.getId())));
    }

    /**
     * Join a group — one live membership per member per group. The
     * gate order is the L47 reaction / L49 RSVP order verbatim: the
     * group gate's honest 404, then the active-membership gate in the
     * group's OWN {@code locationId} (403 — a REJECTED verification
     * cannot join, and a member of a different neighborhood is the
     * same 403), then the one-membership check (409 — the product's
     * own «عضوية واحدة لكل جار»; the V91 partial unique index is the
     * backstop — the reactions' no-lock model, an id-pair write with
     * no capacity to count). Only then the insert.
     */
    @Observed(name = "community.group.join")
    public NeighborhoodGroupMembershipView join(UUID memberId, UUID groupId) {
        NeighborhoodGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        requireWritableMembershipIn(memberId, group.getLocationId(),
                "Join a neighborhood before joining its groups (PUT /api/v1/me/neighborhood)",
                "Only members of the group's neighborhood can join it");
        if (membershipRepository.findByGroupIdAndMemberId(groupId, memberId).isPresent()) {
            throw new ConflictException(
                    "One membership per member per group — leave before joining again");
        }
        NeighborhoodGroupMembership saved =
                membershipRepository.save(NeighborhoodGroupMembership.join(groupId, memberId));
        return NeighborhoodGroupMembershipView.of(saved);
    }

    /**
     * Leave a group — remove the caller's own LIVE membership. The
     * gate order matches {@link #join(UUID, UUID)} (the group gate's
     * honest 404, then the membership gate's 403), and a member with
     * no live membership on the group answers the honest 404 (there is
     * nothing to leave). The leave is the house soft delete — the row
     * stays (b-5's retention, the Envers trail keeps the revision) and
     * the seat is free for a fresh join.
     */
    @Observed(name = "community.group.leave")
    public void leave(UUID memberId, UUID groupId) {
        NeighborhoodGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        requireWritableMembershipIn(memberId, group.getLocationId(),
                "Join a neighborhood before joining its groups (PUT /api/v1/me/neighborhood)",
                "Only members of the group's neighborhood can join it");
        NeighborhoodGroupMembership membership =
                membershipRepository.findByGroupIdAndMemberId(groupId, memberId)
                        .orElseThrow(() -> new ResourceNotFoundException("Group membership", groupId));
        membershipRepository.delete(membership);
    }

    /**
     * L51: the grouped LIVE member count per group over the page's ids
     * — the empty page short-circuits to the empty map (a closed
     * board costs no aggregate).
     */
    private Map<UUID, Long> memberCounts(List<NeighborhoodGroup> groups) {
        if (groups.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = groups.stream().map(NeighborhoodGroup::getId).toList();
        return membershipRepository.countByGroupIdIn(ids).stream()
                .collect(Collectors.toMap(
                        NeighborhoodGroupMembershipRepository.GroupMembershipCount::getGroupId,
                        NeighborhoodGroupMembershipRepository.GroupMembershipCount::getTotalCount));
    }

    /**
     * L51: the caller's own live membership group ids across the
     * page's groups — the joined-state projection's one per-reader
     * fact.
     */
    private Set<UUID> myGroups(UUID callerId, List<NeighborhoodGroup> groups) {
        if (groups.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = groups.stream().map(NeighborhoodGroup::getId).toList();
        return membershipRepository.findByMemberIdAndGroupIdIn(callerId, ids).stream()
                .map(NeighborhoodGroupMembership::getGroupId)
                .collect(Collectors.toSet());
    }

    /**
     * The caller's membership — ANY verification state reads (D-N3:
     * REJECTED blocks community writes, never the board). Absent
     * membership ⇒ the explicit 403.
     */
    private NeighborhoodMembership requireMembership(UUID callerId, String noMembershipMessage) {
        return hoodMembershipRepository.findByUserId(callerId)
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
