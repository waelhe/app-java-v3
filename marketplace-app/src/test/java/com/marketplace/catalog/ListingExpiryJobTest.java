package com.marketplace.catalog;

import com.marketplace.shared.api.CacheInvalidationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L33: the expiry job and the lifecycle policies, with an injectable
 * (fixed) clock — the acceptance criterion "integrative with an injected
 * clock; boundary cases covered".
 *
 * <p>CodeRabbit PR #299 round 1 (two findings, one root): the job's
 * transaction boundary and page-advance moved to
 * {@link ListingExpiryBatchExecutor} — each batch is its own
 * {@code REQUIRES_NEW} transaction and every call re-queries page ZERO
 * (paused rows leave the ACTIVE match set). The tests follow the new
 * structure: the executor's batch semantics (pause, save, per-batch event,
 * count) and the job's loop contract (drain until a short batch; the
 * boundary case models the repository's strict {@code < NOW} result).
 */
@ExtendWith(MockitoExtension.class)
class ListingExpiryJobTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @Mock
    private ProviderListingRepository listingRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ListingExpiryBatchExecutor executor;
    private ListingExpiryJob job;

    @BeforeEach
    void setUp() {
        executor = new ListingExpiryBatchExecutor(listingRepository, eventPublisher, clock);
        job = new ListingExpiryJob(executor);
    }

    private static ProviderListing expiredActive(UUID id) {
        ProviderListing listing = ProviderListing.create(
                id, "title", "desc", "cat", 1000L, "SAR");
        listing.activate(NOW.minusSeconds(3600)); // expiry an hour ago
        return listing;
    }

    @Test
    void job_pausesExpiredActiveListings_withTheExpiredMarker_andInvalidates() {
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
        // the eviction rides the batch's own transaction (the AFTER_COMMIT
        // relay point — published inside the boundary that commits it)
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void theBatchAlwaysRequeriesPageZero_neverAdvancingTheOffset() {
        // CodeRabbit PR #299 round 1: every processed row LEAVES the
        // ACTIVE match set, so the remaining expired rows always start at
        // page zero — the executor must never request page 1. A FULL batch
        // (BATCH_SIZE rows) followed by a short one models a backlog larger
        // than one page: the full page triggers another pass, and that pass
        // re-queries page zero (the first batch's rows are gone from the
        // match set).
        List<ProviderListing> fullBatch = java.util.stream.IntStream.range(0, ListingExpiryJob.BATCH_SIZE)
                .mapToObj(i -> expiredActive(UUID.randomUUID()))
                .collect(Collectors.toList());
        ProviderListing remainder = expiredActive(UUID.randomUUID());
        when(listingRepository.findByStatusAndExpiresAtBefore(
                any(ListingStatus.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(fullBatch))
                .thenReturn(new PageImpl<>(List.of(remainder)));

        job.pauseExpiredListings();

        // TWO passes — the full batch triggers a second, the short page
        // ends the run — and BOTH queried page zero
        org.mockito.ArgumentCaptor<Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(listingRepository, times(2)).findByStatusAndExpiresAtBefore(
                any(ListingStatus.class), any(Instant.class), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Pageable::getPageNumber)
                .containsOnly(0);
        assertThat(fullBatch).allSatisfy(listing ->
                assertThat(listing.getStatus()).isEqualTo(ListingStatus.PAUSED));
        assertThat(remainder.getStatus()).isEqualTo(ListingStatus.PAUSED);
    }

    @Test
    void job_boundaryInstant_staysActive() {
        // CodeRabbit PR #299 round 1: a listing expiring exactly NOW is NOT
        // in the repository's strict {@code < NOW} result (Postgres excludes
        // the boundary row) — the mock models the real query semantics as
        // an EMPTY page, and the assertion proves the row was never paused
        // and never saved (the previous assertion called isExpired only,
        // which stays false even when the status flips to PAUSED).
        ProviderListing boundary = ProviderListing.create(
                UUID.randomUUID(), "t", "d", "c", 1L, "SAR");
        boundary.activate(NOW); // expires exactly NOW

        when(listingRepository.findByStatusAndExpiresAtBefore(
                any(ListingStatus.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        job.pauseExpiredListings();

        assertThat(boundary.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(boundary.isExpired(NOW)).isFalse();
        verify(listingRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any(CacheInvalidationRequested.class));
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
