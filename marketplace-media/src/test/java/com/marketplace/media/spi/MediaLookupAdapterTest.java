package com.marketplace.media.spi;

import com.marketplace.media.MediaAssetRepository;
import com.marketplace.media.MediaAssetStatus;
import org.junit.jupiter.api.Test;

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
 */
class MediaLookupAdapterTest {

    private final MediaAssetRepository mediaAssetRepository = mock(MediaAssetRepository.class);
    private final MediaLookupAdapter adapter = new MediaLookupAdapter(mediaAssetRepository);

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
}
