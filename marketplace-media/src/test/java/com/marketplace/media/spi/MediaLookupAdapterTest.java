package com.marketplace.media.spi;

import com.marketplace.media.MediaAssetRepository;
import com.marketplace.media.MediaAssetStatus;
import com.marketplace.media.MediaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L38 (realestate systems plan — completeness score): the lookup adapter
 * delegates to the derived count query with the FIXED UPLOADED status —
 * the port's storage-verified-only contract. The delegation shape mirrors
 * {@code MediaExportAdapterTest} (plain Mockito, no Spring).
 *
 * <p><b>L48:</b> the adapter widened with the feed's grouped post-media
 * read — still the pure delegation seam: the query and presigning live
 * in {@code MediaService.listByPostIds}, the adapter only forwards.
 */
class MediaLookupAdapterTest {

    private final MediaAssetRepository mediaAssetRepository = mock(MediaAssetRepository.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final MediaLookupAdapter adapter = new MediaLookupAdapter(mediaAssetRepository, mediaService);

    @Test
    void countsWithTheFixedUploadedStatus() {
        UUID listing = UUID.randomUUID();
        when(mediaAssetRepository.countByListingIdAndStatus(listing, MediaAssetStatus.UPLOADED))
                .thenReturn(2L);

        long count = adapter.countUploadedByListing(listing);

        assertEquals(2L, count);
        // The contract is fixed to UPLOADED — a PENDING_UPLOAD row never
        // counts toward the completeness photo quarter.
        verify(mediaAssetRepository).countByListingIdAndStatus(listing, MediaAssetStatus.UPLOADED);
    }

    @Test
    void zeroPassesThroughHonestly() {
        UUID listing = UUID.randomUUID();
        when(mediaAssetRepository.countByListingIdAndStatus(listing, MediaAssetStatus.UPLOADED))
                .thenReturn(0L);

        assertEquals(0L, adapter.countUploadedByListing(listing));
    }

    /** L48: the feed's grouped read forwards to the service (presigning's home). */
    @Test
    void postMediaDelegatesToTheServiceRead() {
        UUID post = UUID.randomUUID();
        var expected = List.of(new com.marketplace.shared.api.MediaLookupPort.PostMediaEntry(
                post, UUID.randomUUID(), "https://u", null, "image/jpeg", 1));
        when(mediaService.listByPostIds(List.of(post))).thenReturn(expected);

        var entries = adapter.findUploadedByPostIds(List.of(post));

        assertEquals(expected, entries);
        verify(mediaService).listByPostIds(List.of(post));
    }
}
