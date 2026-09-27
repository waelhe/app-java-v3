package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L33: the lifecycle policies (the resolve/409/cooldown rules) as plain
 * unit tests against the real service — the Spring-slice variant cannot
 * observe the event publisher (the context resolves the constructor's
 * ApplicationEventPublisher to itself, a documented Spring resolvable
 * dependency, so the mock stays untouched there). The expiry tests of the
 * Spring slice live on where they always did; these policies belong to
 * the module's own coverage.
 */
@ExtendWith(MockitoExtension.class)
class ListingExpiryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ProviderListingRepository listingRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;
    @Mock
    private com.marketplace.shared.api.ProviderNameResolver providerNameResolver;
    @Mock
    private com.marketplace.shared.api.ProviderLookupPort providerLookupPort;

    private CatalogService service;
    private final org.springframework.security.core.Authentication authentication =
            mock(org.springframework.security.core.Authentication.class);

    @org.mockito.Mock
    private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        service = new CatalogService(listingRepository, currentUserProvider,
                providerNameResolver, eventPublisher, providerLookupPort,
                CLOCK, new CatalogProperties(new CatalogProperties.Expiry(90, 1),
                    new CatalogProperties.Seo("", "/listings/{id}", java.util.List.of()),
                    new CatalogProperties.Views("test-key", java.time.Duration.ofDays(1))),
                categoryRepository);
    }

    private ProviderListing draftListing() {
        ProviderListing listing = ProviderListing.create(
                UUID.randomUUID(), "title", "desc", "cat", 1000L, "SAR");
        when(listingRepository.findById(listing.getId())).thenReturn(Optional.of(listing));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        return listing;
    }

    @Test
    void activate_resolvesTheWindowFromThePolicy_andPublishesInvalidation() {
        ProviderListing draft = draftListing();

        service.activate(draft.getId(), null, authentication);

        assertThat(draft.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(draft.getExpiresAt()).isEqualTo(NOW.plusSeconds(90 * 24 * 3600L));
        ArgumentCaptor<com.marketplace.shared.api.CacheInvalidationRequested> captor =
                ArgumentCaptor.forClass(com.marketplace.shared.api.CacheInvalidationRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().cacheNames())
                .containsExactlyInAnyOrderElementsOf(CatalogService.CATALOG_CACHE_NAMES);
    }

    @Test
    void activate_withExplicitDate_winsOverThePolicy() {
        ProviderListing draft = draftListing();
        Instant explicit = NOW.plusSeconds(7 * 24 * 3600L);

        service.activate(draft.getId(), explicit, authentication);

        assertThat(draft.getExpiresAt()).isEqualTo(explicit);
    }

    @Test
    void activate_withPastOrCurrentExplicitDate_is400() {
        // CodeRabbit PR #299 round 1: a past or current expiry would make
        // the listing publicly ACTIVE until the next job tick — the
        // boundary is strictly future (the same rule renewal enforces).
        ProviderListing draft = draftListing();

        assertThatThrownBy(() -> service.activate(draft.getId(), NOW.minusSeconds(60), authentication))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("future");
        assertThatThrownBy(() -> service.activate(draft.getId(), NOW, authentication))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("future");
        // and nothing changed — the draft is untouched
        assertThat(draft.getStatus()).isEqualTo(ListingStatus.DRAFT);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void activate_onTheExpiredPause_is409_theRenewalPathOwnsIt() {
        // CodeRabbit PR #299 round 1: activating an EXPIRED pause would
        // bypass the renewal cooldown and renewedAt tracking — renew owns
        // that transition.
        ProviderListing expired = draftListing();
        expired.activate(NOW.minusSeconds(90 * 24 * 3600L));
        expired.pauseForExpiry();

        assertThatThrownBy(() -> service.activate(expired.getId(), NOW.plusSeconds(3600), authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("renewal path");
        assertThat(expired.getStatus()).isEqualTo(ListingStatus.PAUSED);
        assertThat(expired.getPausedReason()).isEqualTo("EXPIRED");
    }

    @Test
    void activate_withoutPolicyAndWithoutExplicitDate_is409() {
        CatalogService unpolicy = new CatalogService(listingRepository, currentUserProvider,
                providerNameResolver, eventPublisher, providerLookupPort,
                CLOCK, new CatalogProperties(new CatalogProperties.Expiry(null, 1),
                new CatalogProperties.Seo("", "/listings/{id}", java.util.List.of()),
                new CatalogProperties.Views("test-key", java.time.Duration.ofDays(1))),
                categoryRepository);
        ProviderListing draft = draftListing();

        assertThatThrownBy(() -> unpolicy.activate(draft.getId(), null, authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expiry policy");
    }

    @Test
    void renew_onTheExpiredPause_extendsTheWindowFromNow() {
        ProviderListing expired = draftListing();
        expired.activate(NOW.minusSeconds(90 * 24 * 3600L)); // the window already passed
        expired.pauseForExpiry();

        service.renew(expired.getId(), authentication);

        assertThat(expired.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(expired.getPausedReason()).isNull();
        assertThat(expired.getRenewedAt()).isEqualTo(NOW);
        assertThat(expired.getExpiresAt()).isEqualTo(NOW.plusSeconds(90 * 24 * 3600L));
    }

    @Test
    void renew_onTheManualPause_is409() {
        ProviderListing manuallyPaused = draftListing();
        manuallyPaused.activate(NOW.plusSeconds(3600));
        manuallyPaused.pause();

        assertThatThrownBy(() -> service.renew(manuallyPaused.getId(), authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("paused_reason=MANUAL");
    }

    @Test
    void renew_insideTheCooldown_is409() {
        ProviderListing expired = draftListing();
        expired.activate(NOW.minusSeconds(90 * 24 * 3600L));
        expired.pauseForExpiry();
        // renewed one hour ago — inside the 1-day cooldown
        expired.renew(NOW.minusSeconds(3600), NOW.minusSeconds(3600).plusSeconds(90 * 24 * 3600L));
        when(listingRepository.findById(expired.getId())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.renew(expired.getId(), authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cooldown");
    }

    @Test
    void renew_afterTheCooldown_succeeds() {
        ProviderListing expired = draftListing();
        expired.activate(NOW.minusSeconds(200 * 24 * 3600L));
        expired.pauseForExpiry();
        // renewed 2 days ago — outside the cooldown, expired again since
        expired.renew(NOW.minusSeconds(2 * 24 * 3600L),
                NOW.minusSeconds(24 * 3600L)); // already expired again
        expired.pauseForExpiry();
        when(listingRepository.findById(expired.getId())).thenReturn(Optional.of(expired));

        service.renew(expired.getId(), authentication);

        assertThat(expired.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(expired.getRenewedAt()).isEqualTo(NOW);
    }
}
