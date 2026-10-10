package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.FollowedSourcesPort;
import com.marketplace.shared.api.GroupLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JT-20 (the discovery waves — AC-20-05) — the generalized follow
 * domain's gates: the write path's self/liveness/idempotence order, the
 * idempotent withdraw and the freed triple's fresh re-follow, and the
 * FollowedSourcesPort union body (the two follow homes, one honest set
 * — USER + GROUP from V177's table, PROVIDER from V93's).
 */
class FollowServiceTest {

    private final FollowRepository repository = mock(FollowRepository.class);
    private final ProviderFollowRepository providerFollowRepository = mock(ProviderFollowRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GroupLookupPort groupLookupPort = mock(GroupLookupPort.class);

    private FollowService service;

    @BeforeEach
    void setUp() {
        service = new FollowService(repository, providerFollowRepository,
                userRepository, groupLookupPort);
    }

    // ------------------------------------------------------------------
    // followUser — the write path's gate order
    // ------------------------------------------------------------------

    @Test
    void followUser_storesThePairAndAnswersTheFreshView() {
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.existsById(targetId)).thenReturn(true);
        when(repository.findByUserIdAndFollowableTypeAndFollowableId(userId, FollowableType.USER, targetId))
                .thenReturn(Optional.empty());
        when(repository.save(any(Follow.class))).thenAnswer(inv -> inv.getArgument(0));

        FollowService.FollowWriteResult result = service.followUser(userId, targetId);

        assertTrue(result.created());
        ArgumentCaptor<Follow> saved = ArgumentCaptor.forClass(Follow.class);
        verify(repository).save(saved.capture());
        assertEquals(userId, saved.getValue().getUserId());
        assertEquals(FollowableType.USER, saved.getValue().getFollowableType());
        assertEquals(targetId, saved.getValue().getFollowableId());
        assertEquals(FollowableType.USER.name(), result.view().followableType());
        assertEquals(targetId, result.view().followableId());
    }

    @Test
    void followUser_selfFollowAnswers400() {
        UUID userId = UUID.randomUUID();

        assertThrows(BadRequestException.class, () -> service.followUser(userId, userId));
        verifyNoInteractions(repository);
    }

    @Test
    void followUser_unknownTargetAnswersTheHonest404() {
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.existsById(targetId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.followUser(userId, targetId));
        verify(repository, never()).save(any(Follow.class));
    }

    @Test
    void followUser_liveReplayAnswersTheStandingRowNotANewOne() {
        // The idempotent contract: POST is "make sure I follow X" — a live
        // replay answers the standing row (created=false), never a second
        // insert and never a 409.
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Follow standing = Follow.create(UUID.randomUUID(), userId, FollowableType.USER, targetId);
        when(userRepository.existsById(targetId)).thenReturn(true);
        when(repository.findByUserIdAndFollowableTypeAndFollowableId(userId, FollowableType.USER, targetId))
                .thenReturn(Optional.of(standing));

        FollowService.FollowWriteResult result = service.followUser(userId, targetId);

        assertFalse(result.created());
        assertEquals(standing.getId(), result.view().id());
        verify(repository, never()).save(any(Follow.class));
    }

    // ------------------------------------------------------------------
    // unfollow — the idempotent withdraw and the freed triple
    // ------------------------------------------------------------------

    @Test
    void unfollowUser_isTheOwnerScopedSoftDelete() {
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Follow follow = Follow.create(UUID.randomUUID(), userId, FollowableType.USER, targetId);
        when(repository.findByUserIdAndFollowableTypeAndFollowableId(userId, FollowableType.USER, targetId))
                .thenReturn(Optional.of(follow));

        service.unfollowUser(userId, targetId);

        verify(repository).delete(follow);
    }

    @Test
    void unfollowUser_aPairYouDoNotHoldIsTheQuietNoOp() {
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(repository.findByUserIdAndFollowableTypeAndFollowableId(userId, FollowableType.USER, targetId))
                .thenReturn(Optional.empty());

        service.unfollowUser(userId, targetId);

        verify(repository, never()).delete(any(Follow.class));
    }

    @Test
    void unfollowFreesTheTripleSoTheRefollowIsAFreshRow() {
        // The b-5 house withdraw form: the soft delete frees the
        // (member, type, source) triple, so the re-follow inserts a FRESH
        // row (the withdrawn one stays — the member's own record).
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Follow withdrawn = Follow.create(UUID.randomUUID(), userId, FollowableType.USER, targetId);
        when(userRepository.existsById(targetId)).thenReturn(true);
        when(repository.findByUserIdAndFollowableTypeAndFollowableId(userId, FollowableType.USER, targetId))
                .thenReturn(Optional.of(withdrawn))
                .thenReturn(Optional.empty());
        when(repository.save(any(Follow.class))).thenAnswer(inv -> inv.getArgument(0));

        service.unfollowUser(userId, targetId);
        verify(repository).delete(withdrawn);

        FollowService.FollowWriteResult refollow = service.followUser(userId, targetId);
        assertTrue(refollow.created());
        ArgumentCaptor<Follow> saved = ArgumentCaptor.forClass(Follow.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNotEqualTo(withdrawn.getId());
    }

    // ------------------------------------------------------------------
    // followGroup — the GROUP leg through the shared seam
    // ------------------------------------------------------------------

    @Test
    void followGroup_checksTheGroupsLivenessThroughThePort() {
        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(groupLookupPort.exists(groupId)).thenReturn(true);
        when(repository.findByUserIdAndFollowableTypeAndFollowableId(userId, FollowableType.GROUP, groupId))
                .thenReturn(Optional.empty());
        when(repository.save(any(Follow.class))).thenAnswer(inv -> inv.getArgument(0));

        FollowService.FollowWriteResult result = service.followGroup(userId, groupId);

        assertTrue(result.created());
        ArgumentCaptor<Follow> saved = ArgumentCaptor.forClass(Follow.class);
        verify(repository).save(saved.capture());
        assertEquals(FollowableType.GROUP, saved.getValue().getFollowableType());
        assertEquals(groupId, saved.getValue().getFollowableId());
    }

    @Test
    void followGroup_unknownGroupAnswersTheHonest404() {
        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(groupLookupPort.exists(groupId)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.followGroup(userId, groupId));
        verify(repository, never()).save(any(Follow.class));
    }

    @Test
    void followGroup_liveReplayAnswersTheStandingRowNotANewOne() {
        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        Follow standing = Follow.create(UUID.randomUUID(), userId, FollowableType.GROUP, groupId);
        when(groupLookupPort.exists(groupId)).thenReturn(true);
        when(repository.findByUserIdAndFollowableTypeAndFollowableId(userId, FollowableType.GROUP, groupId))
                .thenReturn(Optional.of(standing));

        FollowService.FollowWriteResult result = service.followGroup(userId, groupId);

        assertFalse(result.created());
        assertEquals(standing.getId(), result.view().id());
        verify(repository, never()).save(any(Follow.class));
    }

    // ------------------------------------------------------------------
    // listFollows — the member's own deterministic list
    // ------------------------------------------------------------------

    @Test
    void listFollows_withoutTypeScansTheWholeStoreAndMapsTheViews() {
        UUID userId = UUID.randomUUID();
        Follow userFollow = Follow.create(UUID.randomUUID(), userId, FollowableType.USER, UUID.randomUUID());
        Follow groupFollow = Follow.create(UUID.randomUUID(), userId, FollowableType.GROUP, UUID.randomUUID());
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(userId))
                .thenReturn(List.of(userFollow, groupFollow));

        List<FollowView> views = service.listFollows(userId, null);

        assertEquals(2, views.size());
        assertEquals(FollowableType.USER.name(), views.get(0).followableType());
        assertEquals(FollowableType.GROUP.name(), views.get(1).followableType());
        assertEquals(userFollow.getCreatedAt(), views.get(0).createdAt());
    }

    @Test
    void listFollows_withTypeRidesTheTypeScopedForm() {
        UUID userId = UUID.randomUUID();
        Follow groupFollow = Follow.create(UUID.randomUUID(), userId, FollowableType.GROUP, UUID.randomUUID());
        when(repository.findByUserIdAndFollowableTypeOrderByCreatedAtDescIdDesc(userId, FollowableType.GROUP))
                .thenReturn(List.of(groupFollow));

        List<FollowView> views = service.listFollows(userId, FollowableType.GROUP);

        assertEquals(1, views.size());
        assertEquals(groupFollow.getFollowableId(), views.get(0).followableId());
        verify(repository, never()).findByUserIdOrderByCreatedAtDescIdDesc(any());
    }

    // ------------------------------------------------------------------
    // The FollowedSourcesPort union body — the two homes, one honest set
    // ------------------------------------------------------------------

    @Test
    void followedSources_unionsTheTwoFollowHomes() {
        // USER + GROUP from V177's table, PROVIDER from V93's — the port's
        // closed vocabulary, the provider sourceId in the provider USER-id
        // space the ListingActivatedEvent carries (the port's contract).
        UUID userId = UUID.randomUUID();
        UUID followedUserId = UUID.randomUUID();
        UUID followedGroupId = UUID.randomUUID();
        UUID followedProviderUserId = UUID.randomUUID();
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(userId)).thenReturn(List.of(
                Follow.create(UUID.randomUUID(), userId, FollowableType.USER, followedUserId),
                Follow.create(UUID.randomUUID(), userId, FollowableType.GROUP, followedGroupId)));
        when(providerFollowRepository.findByUserIdOrderByCreatedAtDescIdDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        ProviderFollow.create(UUID.randomUUID(), userId, followedProviderUserId))));

        java.util.Set<FollowedSourcesPort.FollowedSource> sources = service.followedSources(userId);

        assertEquals(3, sources.size());
        assertThat(sources).contains(
                new FollowedSourcesPort.FollowedSource("USER", followedUserId),
                new FollowedSourcesPort.FollowedSource("GROUP", followedGroupId),
                new FollowedSourcesPort.FollowedSource("PROVIDER", followedProviderUserId));
    }

    @Test
    void followedSources_anEmptyPairOfHomesIsTheQuietEmptySet() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(userId)).thenReturn(List.of());
        when(providerFollowRepository.findByUserIdOrderByCreatedAtDescIdDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.followedSources(userId)).isEmpty();
    }
}
