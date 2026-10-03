package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.FollowedProviderNewListingEvent;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W4 (yelp-level plan §5 — G21) — the follow domain's gates and the
 * activation bridge: the write path's resolution/self/duplicate order, the
 * owner-scoped withdraw, and the bridge's exactly-one-alert-per-pair
 * ledger semantics (the saved-search matcher's own unit contract).
 */
class ProviderFollowServiceTest {

    private final ProviderFollowRepository repository = mock(ProviderFollowRepository.class);
    private final ProviderLookupPort providerLookupPort = mock(ProviderLookupPort.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private ProviderFollowService service;

    @BeforeEach
    void setUp() {
        service = new ProviderFollowService(repository, providerLookupPort,
                eventPublisher, jdbcTemplate);
    }

    private static ProviderSummary provider(UUID profileId, UUID userId) {
        return new ProviderSummary(profileId, "L4 provider", "PENDING", userId);
    }

    // ------------------------------------------------------------------
    // The write path
    // ------------------------------------------------------------------

    @Test
    void create_resolvesTheProfileIdAndStoresTheUserSpaceId() {
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID providerUserId = UUID.randomUUID();
        when(providerLookupPort.findById(profileId)).thenReturn(Optional.of(provider(profileId, providerUserId)));
        when(repository.existsByUserIdAndProviderUserId(userId, providerUserId)).thenReturn(false);
        when(repository.save(any(ProviderFollow.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProviderFollowView view = service.create(userId, profileId);

        // The stored row keeps the USER-space id (A1 — the bridge's join key)
        ArgumentCaptor<ProviderFollow> saved = ArgumentCaptor.forClass(ProviderFollow.class);
        verify(repository).save(saved.capture());
        assertEquals(userId, saved.getValue().getUserId());
        assertEquals(providerUserId, saved.getValue().getProviderUserId());
        // and the view answers the PUBLIC page's key (the profile id)
        assertEquals(profileId, view.providerId());
        assertEquals("L4 provider", view.providerDisplayName());
    }

    @Test
    void create_unknownProviderAnswersTheHonest404() {
        when(providerLookupPort.findById(any())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.create(UUID.randomUUID(), UUID.randomUUID()));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void create_selfFollowAnswers400() {
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(providerLookupPort.findById(profileId))
                .thenReturn(Optional.of(provider(profileId, userId)));

        assertThrows(BadRequestException.class, () -> service.create(userId, profileId));
    }

    @Test
    void create_liveDuplicateAnswers409() {
        UUID userId = UUID.randomUUID();
        UUID providerUserId = UUID.randomUUID();
        when(providerLookupPort.findById(any()))
                .thenReturn(Optional.of(provider(UUID.randomUUID(), providerUserId)));
        when(repository.existsByUserIdAndProviderUserId(userId, providerUserId)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.create(userId, UUID.randomUUID()));
    }

    // ------------------------------------------------------------------
    // The /me reads
    // ------------------------------------------------------------------

    @Test
    void listFor_composesThePageWithOneBatchProviderResolution() {
        // The W1 findAllByIds N+1 rule — the whole page's providers resolve
        // in ONE findAllByUserIds call, composed by the service so the
        // controller boundary speaks the view record only.
        UUID userId = UUID.randomUUID();
        UUID providerUserId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        ProviderFollow follow = ProviderFollow.create(UUID.randomUUID(), userId, providerUserId);
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable))
                .thenReturn(new PageImpl<>(List.of(follow)));
        when(providerLookupPort.findAllByUserIds(java.util.Set.of(providerUserId)))
                .thenReturn(java.util.Map.of(providerUserId, provider(profileId, providerUserId)));

        org.springframework.data.domain.Page<ProviderFollowView> page =
                service.listFor(userId, pageable);

        verify(repository).findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        verify(providerLookupPort).findAllByUserIds(java.util.Set.of(providerUserId));
        assertEquals(1, page.getContent().size());
        assertEquals(profileId, page.getContent().get(0).providerId());
        assertEquals("L4 provider", page.getContent().get(0).providerDisplayName());
        assertEquals(follow.getCreatedAt(), page.getContent().get(0).createdAt());
    }

    @Test
    void listFor_anEmptyPageCostsNoProviderQueryAtAll() {
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.listFor(userId, pageable)).isEmpty();

        verify(providerLookupPort, never()).findAllByUserIds(any());
    }

    @Test
    void delete_isTheOwnerScopedSoftDelete() {
        UUID userId = UUID.randomUUID();
        ProviderFollow follow = ProviderFollow.create(UUID.randomUUID(), userId, UUID.randomUUID());
        when(repository.findByIdAndUserId(follow.getId(), userId)).thenReturn(Optional.of(follow));

        service.delete(userId, follow.getId());

        verify(repository).delete(follow);
    }

    @Test
    void delete_foreignIdIsTheHonest404() {
        UUID userId = UUID.randomUUID();
        UUID followId = UUID.randomUUID();
        when(repository.findByIdAndUserId(followId, userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(userId, followId));
        verify(repository, never()).delete(any(ProviderFollow.class));
    }

    // ------------------------------------------------------------------
    // The activation bridge
    // ------------------------------------------------------------------

    @Test
    void onListingActivated_publishesOneEventPerNewLedgerRow() {
        UUID providerUserId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID followerA = UUID.randomUUID();
        UUID followerB = UUID.randomUUID();
        when(repository.findByProviderUserId(providerUserId)).thenReturn(List.of(
                ProviderFollow.create(UUID.randomUUID(), followerA, providerUserId),
                ProviderFollow.create(UUID.randomUUID(), followerB, providerUserId)));
        when(jdbcTemplate.update(anyString(), any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(1);

        int alerted = service.onListingActivated(listingId, providerUserId);

        assertEquals(2, alerted);
        ArgumentCaptor<FollowedProviderNewListingEvent> events =
                ArgumentCaptor.forClass(FollowedProviderNewListingEvent.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertEquals(List.of(followerA, followerB),
                events.getAllValues().stream().map(FollowedProviderNewListingEvent::recipientId).toList());
    }

    @Test
    void onListingActivated_aSkippedLedgerRowPublishesNothing() {
        // The "تنبيهًا واحدًا" guarantee: a pair already in the ledger (a
        // registry re-delivery of the same activation) reads 0 from the
        // ON CONFLICT insert and is never re-published.
        UUID providerUserId = UUID.randomUUID();
        UUID followerId = UUID.randomUUID();
        when(repository.findByProviderUserId(providerUserId)).thenReturn(List.of(
                ProviderFollow.create(UUID.randomUUID(), followerId, providerUserId)));
        when(jdbcTemplate.update(anyString(), any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(0);

        int alerted = service.onListingActivated(UUID.randomUUID(), providerUserId);

        assertEquals(0, alerted);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onListingActivated_noFollowersIsTheQuietZero() {
        when(repository.findByProviderUserId(any())).thenReturn(List.of());

        assertEquals(0, service.onListingActivated(UUID.randomUUID(), UUID.randomUUID()));
        verifyNoInteractions(jdbcTemplate, eventPublisher);
    }

    @Test
    void onListingActivated_insertsTheLedgerRowWithOnConflictDoNothing() {
        UUID providerUserId = UUID.randomUUID();
        UUID followerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        when(repository.findByProviderUserId(providerUserId)).thenReturn(List.of(
                ProviderFollow.create(UUID.randomUUID(), followerId, providerUserId)));
        when(jdbcTemplate.update(anyString(), any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(1);

        service.onListingActivated(listingId, providerUserId);

        verify(jdbcTemplate).update(
                eq("INSERT INTO provider_follow_alerts (id, user_id, listing_id, alerted_at) "
                        + "VALUES (?, ?, ?, now()) ON CONFLICT DO NOTHING"),
                any(UUID.class), eq(followerId), eq(listingId));
    }
}
