package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L33 entity gates: the durable pause markers, the no-immortal-listing
 * floor, the EXPIRED-only renewal, and the boundary instant semantics.
 */
class ListingExpiryEntityTest {

    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(90 * 24 * 3600);

    private static ProviderListing listing(ListingStatus status) {
        ProviderListing listing = ProviderListing.create(
                java.util.UUID.randomUUID(), "title", "desc", "cat", 1000L, "SAR");
        switch (status) {
            case ACTIVE -> listing.activate(LATER);
            case PAUSED -> {
                listing.activate(LATER);
                listing.pause();
            }
            case ARCHIVED -> {
                listing.activate(LATER);
                listing.archive();
            }
            default -> { }
        }
        return listing;
    }

    @Test
    void activate_withoutExpiry_isRefused_noSilentlyImmortalListing() {
        ProviderListing draft = listing(ListingStatus.DRAFT);

        assertThatThrownBy(() -> draft.activate(null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expiry");
    }

    @Test
    void activate_setsTheWindowAndClearsAnyStaleMarker() {
        ProviderListing pausedManually = listing(ListingStatus.PAUSED);
        assertThat(pausedManually.getPausedReason()).isEqualTo("MANUAL");

        pausedManually.activate(LATER);

        assertThat(pausedManually.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(pausedManually.getExpiresAt()).isEqualTo(LATER);
        assertThat(pausedManually.getPausedReason()).isNull();
    }

    @Test
    void pause_marksMANUAL_durably() {
        ProviderListing active = listing(ListingStatus.ACTIVE);

        active.pause();

        assertThat(active.getPausedReason()).isEqualTo("MANUAL");
    }

    @Test
    void pauseForExpiry_marksEXPIRED_durably() {
        ProviderListing active = listing(ListingStatus.ACTIVE);

        active.pauseForExpiry();

        assertThat(active.getStatus()).isEqualTo(ListingStatus.PAUSED);
        assertThat(active.getPausedReason()).isEqualTo("EXPIRED");
    }

    @Test
    void renew_worksOnlyOnTheExpiredPause() {
        ProviderListing expired = listing(ListingStatus.ACTIVE);
        expired.pauseForExpiry();

        expired.renew(NOW, LATER);

        assertThat(expired.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(expired.getExpiresAt()).isEqualTo(LATER);
        assertThat(expired.getRenewedAt()).isEqualTo(NOW);
        assertThat(expired.getPausedReason()).isNull();
    }

    @Test
    void renew_onTheManualPause_is409() {
        ProviderListing manuallyPaused = listing(ListingStatus.PAUSED);

        assertThatThrownBy(() -> manuallyPaused.renew(NOW, LATER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("paused_reason=MANUAL");
    }

    @Test
    void renew_onActive_is409() {
        ProviderListing active = listing(ListingStatus.ACTIVE);

        assertThatThrownBy(() -> active.renew(NOW, LATER))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void renew_requiresAFutureWindow() {
        ProviderListing expired = listing(ListingStatus.ACTIVE);
        expired.pauseForExpiry();

        assertThatThrownBy(() -> expired.renew(NOW, NOW.minusSeconds(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("future");
    }

    @Test
    void isExpired_strictlyBefore_TheBoundaryInstantIsStillLive() {
        ProviderListing active = listing(ListingStatus.ACTIVE); // expires LATER

        assertThat(active.isExpired(NOW)).isFalse();
        assertThat(active.isExpired(LATER)).isFalse(); // boundary: still live
        assertThat(active.isExpired(LATER.plusSeconds(1))).isTrue();
    }

    @Test
    void isExpired_legacyNullExpiry_neverExpires() {
        ProviderListing legacy = listing(ListingStatus.DRAFT); // expiresAt never set

        assertThat(legacy.isExpired(NOW)).isFalse();
        assertThat(legacy.isExpired(Instant.MAX)).isFalse();
    }
}
