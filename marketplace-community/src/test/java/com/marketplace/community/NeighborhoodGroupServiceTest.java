package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L51 — the groups board service's gate orders, unit-pinned (the
 * plan's acceptance criteria; the module integration test proves the
 * same against the real schema):
 *
 * <ul>
 *   <li>the join gate order: the group's honest 404 → the active,
 *       writable membership in exactly the group's own neighborhood
 *       (403 — absent, a different neighborhood, or a REJECTED
 *       verification) → the one-membership check (409) → insert;</li>
 *   <li>the leave gate (the review round's correction — the /me
 *       owner-delete convention): the group's honest 404 → the honest
 *       404 for a member with no live membership → the house soft
 *       delete; the join's neighborhood gate NEVER rides the leave — a
 *       former neighbor (absent membership, or switched to another
 *       neighborhood) can still take his stale membership with him;</li>
 *   <li>the board read gate: no active membership ⇒ 403 (G-N3's
 *       default); the board carries the LIVE member count (one grouped
 *       aggregate over the page's group ids) + joinedByMe (the
 *       caller's own membership) per row; the empty page costs neither
 *       read.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodGroupServiceTest {

    @Mock
    private NeighborhoodGroupRepository groupRepository;

    @Mock
    private NeighborhoodGroupMembershipRepository membershipRepository;

    @Mock
    private NeighborhoodMembershipRepository hoodMembershipRepository;

    private NeighborhoodGroupService service;

    @BeforeEach
    void setUp() {
        service = new NeighborhoodGroupService(groupRepository, membershipRepository,
                hoodMembershipRepository);
    }

    private UUID callerId = UUID.randomUUID();
    private UUID otherMemberId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();
    private UUID groupId = UUID.randomUUID();

    private NeighborhoodGroup groupOf(UUID location) {
        return NeighborhoodGroup.founded(location, "فريق دراجي ومشي النخيل",
                "تجمّع يومي 5:30 فجراً");
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

    // ---------- join: the gate order ----------

    @Test
    void join_unknownGroup_isTheHonest404() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join(callerId, groupId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void join_noMembership_is403() {
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        when(hoodMembershipRepository.findByUserId(callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join(callerId, groupId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void join_membershipInAnotherNeighborhood_is403() {
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        when(hoodMembershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.join(callerId, groupId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("group's neighborhood");
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void join_rejectedVerification_is403TheWriteGate() {
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        NeighborhoodMembership rejected = membershipRejected(callerId, locationId);
        when(hoodMembershipRepository.findByUserId(callerId)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.join(callerId, groupId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Rejected neighborhood verification");
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void join_alreadyAMember_is409OneMembershipPerMember() {
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        when(hoodMembershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        when(membershipRepository.findByGroupIdAndMemberId(groupId, callerId))
                .thenReturn(Optional.of(NeighborhoodGroupMembership.join(groupId, callerId)));

        assertThatThrownBy(() -> service.join(callerId, groupId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("One membership per member per group");
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void join_memberOfTheGroupsOwnNeighborhood_savesAndEchoesTheMembership() {
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        when(hoodMembershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        NeighborhoodGroupMembership saved = NeighborhoodGroupMembership.join(groupId, callerId);
        when(membershipRepository.save(any())).thenReturn(saved);

        NeighborhoodGroupMembershipView view = service.join(callerId, groupId);

        assertThat(view.groupId()).isEqualTo(groupId);
        assertThat(view.memberId()).isEqualTo(callerId);
        assertThat(view.id()).isEqualTo(saved.getId());
        verify(membershipRepository).save(any());
    }

    // ---------- leave: the owner-scoped removal (the review round's
    // correction — the /me owner-delete convention) ----------

    @Test
    void leave_unknownGroup_isTheHonest404() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(callerId, groupId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(membershipRepository, never()).delete(any());
    }

    @Test
    void leave_noNeighborhoodMembershipAtAll_stillRemovesTheStaleRow() {
        // The review round's P1 regression: a member who left the
        // neighborhood entirely (no hood row at all) must still be able
        // to take his stale group membership with him — the old shape
        // answered 403 and the stale row was locked in forever.
        // Deliberately NO hood-membership stub: the owner-scoped leave
        // never consults the neighborhood — that is the regression's
        // own point (the old shape answered 403 here).
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        NeighborhoodGroupMembership stale = NeighborhoodGroupMembership.join(groupId, callerId);
        when(membershipRepository.findByGroupIdAndMemberId(groupId, callerId))
                .thenReturn(Optional.of(stale));

        service.leave(callerId, groupId);

        verify(membershipRepository).delete(stale);
    }

    @Test
    void leave_formerNeighbor_whoSwitchedHoods_removesTheStaleMembership() {
        // The switch is a soft-delete+insert of the hood row (the
        // membership service's own measured semantics) — nothing
        // cascades to the group row, so the leave must not consult the
        // hood at all (again deliberately unstubbed): the stale
        // membership is the caller's OWN row.
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        NeighborhoodGroupMembership stale = NeighborhoodGroupMembership.join(groupId, callerId);
        when(membershipRepository.findByGroupIdAndMemberId(groupId, callerId))
                .thenReturn(Optional.of(stale));

        service.leave(callerId, groupId);

        verify(membershipRepository).delete(stale);
    }

    @Test
    void leave_noLiveMembership_isTheHonest404() {
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        when(membershipRepository.findByGroupIdAndMemberId(groupId, callerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(callerId, groupId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Group membership");
        verify(membershipRepository, never()).delete(any());
    }

    @Test
    void leave_liveMembership_isTheHouseSoftDelete() {
        when(groupRepository.findById(groupId))
                .thenReturn(Optional.of(groupOf(locationId)));
        NeighborhoodGroupMembership live = NeighborhoodGroupMembership.join(groupId, callerId);
        when(membershipRepository.findByGroupIdAndMemberId(groupId, callerId))
                .thenReturn(Optional.of(live));

        service.leave(callerId, groupId);

        verify(membershipRepository).delete(live);
    }

    // ---------- getBoard ----------

    @Test
    void getBoard_noMembership_is403() {
        when(hoodMembershipRepository.findByUserId(callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBoard(callerId, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(groupRepository, never()).findByLocationId(any(UUID.class), any(Pageable.class));
    }

    @Test
    void getBoard_carriesTheLiveCountAndJoinedByMePerRow() {
        when(hoodMembershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        NeighborhoodGroup joined = groupOf(locationId);
        NeighborhoodGroup open = groupOf(locationId);
        when(groupRepository.findByLocationId(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(joined, open)));
        when(membershipRepository.countByGroupIdIn(anyCollection()))
                .thenReturn(List.of(countOf(joined.getId(), 7),
                        countOf(open.getId(), 1)));
        when(membershipRepository.findByMemberIdAndGroupIdIn(any(UUID.class), anyCollection()))
                .thenReturn(List.of(NeighborhoodGroupMembership.join(joined.getId(), callerId)));

        Page<NeighborhoodGroupView> board = service.getBoard(callerId, PageRequest.of(0, 20));

        assertThat(board.getContent()).hasSize(2);
        NeighborhoodGroupView joinedView = board.getContent().get(0);
        assertThat(joinedView.members()).isEqualTo(7);
        assertThat(joinedView.joinedByMe()).isTrue();
        assertThat(joinedView.name()).isEqualTo("فريق دراجي ومشي النخيل");
        assertThat(joinedView.description()).isEqualTo("تجمّع يومي 5:30 فجراً");
        NeighborhoodGroupView openView = board.getContent().get(1);
        assertThat(openView.members()).isEqualTo(1);
        assertThat(openView.joinedByMe()).isFalse();
    }

    @Test
    void getBoard_emptyPage_costsNoAggregateAndNoMembershipRead() {
        when(hoodMembershipRepository.findByUserId(callerId))
                .thenReturn(Optional.of(membershipOf(callerId, locationId)));
        when(groupRepository.findByLocationId(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<NeighborhoodGroupView> board = service.getBoard(callerId, PageRequest.of(0, 20));

        assertThat(board.getContent()).isEmpty();
        verify(membershipRepository, never()).countByGroupIdIn(anyCollection());
        verify(membershipRepository, never()).findByMemberIdAndGroupIdIn(any(UUID.class),
                anyCollection());
    }

    /** The grouped count projection's own little builder (Spring Data's
     *  interface projection mocked by hand — the EventRsvpCount idiom). */
    private NeighborhoodGroupMembershipRepository.GroupMembershipCount countOf(UUID groupId,
                                                                              long total) {
        return new NeighborhoodGroupMembershipRepository.GroupMembershipCount() {
            @Override
            public UUID getGroupId() { return groupId; }

            @Override
            public long getTotalCount() { return total; }
        };
    }
}
