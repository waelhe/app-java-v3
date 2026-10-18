package com.marketplace.provider;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W5b (the journeys register's J29 row — the provider team delegation): the
 * delegation engine's full matrix, on the {@code UrgentAlertServiceTest}
 * shape — the gate ORDER before any write (unknown provider 404 →
 * non-owner 403 → member==owner 409 → duplicate live seat 409 → the role
 * vocabulary 400), the unique-index race backstop (the flush's
 * {@link DataIntegrityViolationException} translated to the same 409), the
 * one-exit lifecycle (revoke only by the owner, only an ACTIVE seat), and
 * the authorization seam's two questions (acting member; editor-or-above).
 */
@ExtendWith(MockitoExtension.class)
class ProviderTeamServiceTest {

    @Mock
    private ProviderRepository providerRepository;

    @Mock
    private ProviderTeamMemberRepository teamRepository;

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();

    private ProviderTeamService service() {
        return new ProviderTeamService(providerRepository, teamRepository);
    }

    /** A profile owned by {@link #OWNER_ID}, on the house fixture shape. */
    private ProviderProfile ownedProfile(UUID providerId) {
        return Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getId), providerId)
                .set(field(ProviderProfile::getUserId), OWNER_ID)
                .set(field(ProviderProfile::getDisplayName), "شركة الاختبار")
                .set(field(ProviderProfile::getStatus), ProviderStatus.PENDING)
                .create();
    }

    /** The owner's delegation context: the provider resolves to its owner. */
    private ProviderProfile ownedProvider() {
        ProviderProfile profile = ownedProfile(UUID.randomUUID());
        when(providerRepository.findById(profile.getId())).thenReturn(Optional.of(profile));
        return profile;
    }

    // -- the grant's guard order (all BEFORE any write) ------------------------

    @Test
    void addMember_unknownProvider_answers404() {
        UUID unknown = Instancio.create(UUID.class);
        when(providerRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addMember(unknown, OWNER_ID, MEMBER_ID, "EDITOR", OWNER_ID.toString()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(teamRepository, never()).saveAndFlush(any(ProviderTeamMember.class));
    }

    @Test
    void addMember_nonOwnerActor_answers403() {
        ProviderProfile provider = ownedProvider();
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service().addMember(provider.getId(), stranger, MEMBER_ID,
                "EDITOR", stranger.toString()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only the provider owner can manage the team");
        verify(teamRepository, never()).saveAndFlush(any(ProviderTeamMember.class));
    }

    @Test
    void addMember_ownerAsMember_answers409() {
        ProviderProfile provider = ownedProvider();

        assertThatThrownBy(() -> service().addMember(provider.getId(), OWNER_ID, OWNER_ID,
                "MANAGER", OWNER_ID.toString()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("The owner needs no delegation");
        verify(teamRepository, never()).existsByProviderIdAndMemberUserIdAndStatus(
                any(UUID.class), any(UUID.class), any(ProviderTeamMemberStatus.class));
        verify(teamRepository, never()).saveAndFlush(any(ProviderTeamMember.class));
    }

    @Test
    void addMember_duplicateLiveSeat_answers409() {
        ProviderProfile provider = ownedProvider();
        when(teamRepository.existsByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), MEMBER_ID, ProviderTeamMemberStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> service().addMember(provider.getId(), OWNER_ID, MEMBER_ID,
                "EDITOR", OWNER_ID.toString()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already holds a live team seat");
        verify(teamRepository, never()).saveAndFlush(any(ProviderTeamMember.class));
    }

    @Test
    void addMember_roleOutsideTheVocabulary_answers400() {
        ProviderProfile provider = ownedProvider();
        when(teamRepository.existsByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), MEMBER_ID, ProviderTeamMemberStatus.ACTIVE)).thenReturn(false);

        assertThatThrownBy(() -> service().addMember(provider.getId(), OWNER_ID, MEMBER_ID,
                "VIEWER", OWNER_ID.toString()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("MANAGER or EDITOR");
        verify(teamRepository, never()).saveAndFlush(any(ProviderTeamMember.class));
    }

    @Test
    void addMember_grantsTheSeatBornActiveWithTheAttribution() {
        ProviderProfile provider = ownedProvider();
        when(teamRepository.existsByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), MEMBER_ID, ProviderTeamMemberStatus.ACTIVE)).thenReturn(false);
        when(teamRepository.saveAndFlush(any(ProviderTeamMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProviderTeamMember granted = service().addMember(provider.getId(), OWNER_ID, MEMBER_ID,
                "MANAGER", OWNER_ID.toString());

        assertThat(granted.getProviderId()).isEqualTo(provider.getId());
        assertThat(granted.getMemberUserId()).isEqualTo(MEMBER_ID);
        assertThat(granted.getRole()).isEqualTo(ProviderTeamRole.MANAGER);
        assertThat(granted.getStatus()).isEqualTo(ProviderTeamMemberStatus.ACTIVE);
        assertThat(granted.getAddedBy()).isEqualTo(OWNER_ID.toString());
    }

    @Test
    void addMember_uniqueIndexRace_translatesToTheSame409() {
        ProviderProfile provider = ownedProvider();
        when(teamRepository.existsByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), MEMBER_ID, ProviderTeamMemberStatus.ACTIVE)).thenReturn(false);
        // Two concurrent adds of the same member both passed the read gate —
        // the partial unique index holds the one-live-seat law and the loser
        // answers the SAME 409 the gate would have taught (the L31 shape).
        when(teamRepository.saveAndFlush(any(ProviderTeamMember.class)))
                .thenThrow(new DataIntegrityViolationException("uq_provider_team_one_live"));

        assertThatThrownBy(() -> service().addMember(provider.getId(), OWNER_ID, MEMBER_ID,
                "EDITOR", OWNER_ID.toString()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already holds a live team seat");
    }

    // -- the withdrawal's own laws ----------------------------------------------

    @Test
    void revoke_onlyByTheOwner_answers403Otherwise() {
        ProviderProfile provider = ownedProvider();
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service().revoke(provider.getId(), stranger, MEMBER_ID))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only the provider owner can manage the team");
        verify(teamRepository, never()).save(any(ProviderTeamMember.class));
    }

    @Test
    void revoke_unknownProvider_answers404() {
        UUID unknown = Instancio.create(UUID.class);
        when(providerRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().revoke(unknown, OWNER_ID, MEMBER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void revoke_foreignMemberRow_answers404() {
        ProviderProfile provider = ownedProvider();
        UUID foreignMemberId = UUID.randomUUID();
        when(teamRepository.findById(foreignMemberId)).thenReturn(Optional.of(
                ProviderTeamMember.create(UUID.randomUUID(), MEMBER_ID,
                        ProviderTeamRole.EDITOR, OWNER_ID.toString())));

        assertThatThrownBy(() -> service().revoke(provider.getId(), OWNER_ID, foreignMemberId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Team member not found for this provider");
    }

    @Test
    void revoke_onlyAnActiveSeat_answers409Otherwise() {
        ProviderProfile provider = ownedProvider();
        ProviderTeamMember revoked = ProviderTeamMember.create(
                provider.getId(), MEMBER_ID, ProviderTeamRole.EDITOR, OWNER_ID.toString());
        revoked.revoke();
        when(teamRepository.findById(revoked.getId())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service().revoke(provider.getId(), OWNER_ID, revoked.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot transition from REVOKED to REVOKED");
        verify(teamRepository, never()).save(any(ProviderTeamMember.class));
    }

    @Test
    void revoke_endsTheAuthorityAndKeepsTheRow() {
        ProviderProfile provider = ownedProvider();
        ProviderTeamMember seat = ProviderTeamMember.create(
                provider.getId(), MEMBER_ID, ProviderTeamRole.EDITOR, OWNER_ID.toString());
        when(teamRepository.findById(seat.getId())).thenReturn(Optional.of(seat));
        when(teamRepository.save(any(ProviderTeamMember.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderTeamMember revoked = service().revoke(provider.getId(), OWNER_ID, seat.getId());

        assertThat(revoked.getStatus()).isEqualTo(ProviderTeamMemberStatus.REVOKED);
        assertThat(revoked.getId()).isEqualTo(seat.getId());
    }

    // -- the roster read ----------------------------------------------------------

    @Test
    void team_isTheOwnersLiveRosterInArrivalOrder() {
        ProviderProfile provider = ownedProvider();
        ProviderTeamMember first = ProviderTeamMember.create(
                provider.getId(), MEMBER_ID, ProviderTeamRole.EDITOR, OWNER_ID.toString());
        ProviderTeamMember second = ProviderTeamMember.create(
                provider.getId(), UUID.randomUUID(), ProviderTeamRole.MANAGER, OWNER_ID.toString());
        when(teamRepository.findByProviderIdAndStatusOrderByCreatedAtAscIdAsc(
                provider.getId(), ProviderTeamMemberStatus.ACTIVE))
                .thenReturn(List.of(first, second));

        List<ProviderTeamMember> roster = service().team(provider.getId(), OWNER_ID);

        assertThat(roster).containsExactly(first, second);
    }

    @Test
    void team_nonOwner_answers403() {
        ProviderProfile provider = ownedProvider();

        assertThatThrownBy(() -> service().team(provider.getId(), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // -- the authorization seam (the J29 value) ------------------------------------

    @Test
    void isActingMember_trueForTheOwnerAndAnyLiveSeat_falseOtherwise() {
        ProviderProfile provider = ownedProvider();

        assertThat(service().isActingMember(provider.getId(), OWNER_ID)).isTrue();

        when(teamRepository.existsByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), MEMBER_ID, ProviderTeamMemberStatus.ACTIVE)).thenReturn(true);
        assertThat(service().isActingMember(provider.getId(), MEMBER_ID)).isTrue();

        // A stranger, a null caller, and an unknown provider all answer the
        // honest negative — an authorization question, not a lookup.
        assertThat(service().isActingMember(provider.getId(), UUID.randomUUID())).isFalse();
        assertThat(service().isActingMember(provider.getId(), null)).isFalse();
        assertThat(service().isActingMember(UUID.randomUUID(), OWNER_ID)).isFalse();
    }

    @Test
    void isActiveEditorOrAbove_trueForOwnerManagerAndEditor_falseForRevoked() {
        ProviderProfile provider = ownedProvider();

        assertThat(service().isActiveEditorOrAbove(provider.getId(), OWNER_ID)).isTrue();

        ProviderTeamMember manager = ProviderTeamMember.create(
                provider.getId(), MEMBER_ID, ProviderTeamRole.MANAGER, OWNER_ID.toString());
        when(teamRepository.findByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), MEMBER_ID, ProviderTeamMemberStatus.ACTIVE))
                .thenReturn(Optional.of(manager));
        assertThat(service().isActiveEditorOrAbove(provider.getId(), MEMBER_ID)).isTrue();

        ProviderTeamMember editor = ProviderTeamMember.create(
                provider.getId(), UUID.randomUUID(), ProviderTeamRole.EDITOR, OWNER_ID.toString());
        UUID editorUserId = editor.getMemberUserId();
        when(teamRepository.findByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), editorUserId, ProviderTeamMemberStatus.ACTIVE))
                .thenReturn(Optional.of(editor));
        assertThat(service().isActiveEditorOrAbove(provider.getId(), editorUserId)).isTrue();

        // A REVOKED seat never resolves through the ACTIVE read — the seam
        // answers false and the write falls to the house 403 (the journey
        // test's final branch).
        UUID revokedUserId = UUID.randomUUID();
        when(teamRepository.findByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), revokedUserId, ProviderTeamMemberStatus.ACTIVE))
                .thenReturn(Optional.empty());
        assertThat(service().isActiveEditorOrAbove(provider.getId(), revokedUserId)).isFalse();
        assertThat(service().isActiveEditorOrAbove(provider.getId(), null)).isFalse();
    }

    @Test
    void isActiveEditorOrAbove_revokedSeat_itselfAnswersFalse() {
        // The live proof the unit seam cannot fake: a REVOKED row exists in
        // the registry, but the seam's ACTIVE-only read never sees it.
        ProviderProfile provider = ownedProvider();
        ProviderTeamMember revoked = ProviderTeamMember.create(
                provider.getId(), MEMBER_ID, ProviderTeamRole.MANAGER, OWNER_ID.toString());
        revoked.revoke();
        assertThat(revoked.getStatus()).isEqualTo(ProviderTeamMemberStatus.REVOKED);
        assertThat(revoked.isActive()).isFalse();
        when(teamRepository.findByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), MEMBER_ID, ProviderTeamMemberStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(service().isActiveEditorOrAbove(provider.getId(), MEMBER_ID)).isFalse();
        verify(teamRepository).findByProviderIdAndMemberUserIdAndStatus(
                eq(provider.getId()), eq(MEMBER_ID), eq(ProviderTeamMemberStatus.ACTIVE));
    }
}
