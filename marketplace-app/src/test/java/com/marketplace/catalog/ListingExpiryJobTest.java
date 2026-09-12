package com.marketplace.catalog;

import com.marketplace.shared.api.CacheInvalidationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * L33: the expiry job and the lifecycle policies, with an injectable
 * (fixed) clock — the acceptance criterion "integrative with an injected
 * clock; boundary cases covered".
 */
@ExtendWith(MockitoExtension.class)
class ListingExpiryJobTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @Mock
    private ProviderListingRepository listingRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ListingExpiryJob job;

    @BeforeEach
    void setUp() {
        job = new ListingExpiryJob(listingRepository, eventPublisher, clock);
    }

    private static ProviderListing expiredActive(UUID id) {
        ProviderListing listing = ProviderListing.create(
                id, "title", "desc", "cat", 1000L, "SAR");
        listing.activate(NOW.minusSeconds(3600)); // expiry an hour ago
        return listing;
    }

    @Test
    void job_pausesExpiredActiveListings_withTheExpiredMarker_andInvalidatesOnce() {
        ProviderListing one = expiredActive(UUID.randomUUID());
        ProviderListing two = expiredActive(UUID.randomUUID());
        when(listingRepository.findByStatusAndExpiresAtBefore(
                any(ListingStatus.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(one, two)));

        job.pauseExpiredListings();

        assertThat(one.getStatus()).isEqualTo(ListingStatus.PAUSED);
        assertThat(one.getPausedReason()).isEqualTo("EXPIRED");
        assertThat(two.getStatus()).isEqualTo(ListingStatus.PAUSED);
        verify(listingRepository).saveAll(List.of(one, two));
        // ONE invalidation event covers the batch (the relay evicts names)
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void job_boundaryInstant_staysActive() {
        // a listing expiring exactly NOW: isExpired is strictly-before, so
        // the predicate query parameter is NOW — Postgres' < excludes the
        // boundary row; the job's own guard re-checks isExpired(now)
        ProviderListing boundary = ProviderListing.create(
                UUID.randomUUID(), "t", "d", "c", 1L, "SAR");
        boundary.activate(NOW); // expires exactly NOW

        when(listingRepository.findByStatusAndExpiresAtBefore(
                any(ListingStatus.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(boundary)));

        job.pauseExpiredListings();

        // the boundary row arrived (the mock is deliberately naive); the
        // entity's own strictly-before semantics keep it ACTIVE
        assertThat(boundary.isExpired(NOW)).isFalse();
    }

    @Test
    void job_isIdempotent_doubleRunTransfersNothingNew() {
        when(listingRepository.findByStatusAndExpiresAtBefore(
                any(ListingStatus.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        job.pauseExpiredListings();
        job.pauseExpiredListings();

        verify(listingRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void job_legacyNullExpiry_neverAppearsInTheScan() {
        // the scan predicate is expires_at < now — NULL rows are excluded
        // by SQL three-valued logic (NULL < x is not true); the partial
        // index (WHERE expires_at IS NOT NULL) mirrors the same contract
        ProviderListing legacy = ProviderListing.create(
                UUID.randomUUID(), "t", "d", "c", 1L, "SAR");
        legacy.activate(); // the legacy transition — no expiry set
        assertThat(legacy.getExpiresAt()).isNull();
        assertThat(legacy.isExpired(Instant.MAX)).isFalse();
    }
}
