package com.marketplace.media.spi;

import com.marketplace.media.MediaAssetRepository;
import com.marketplace.media.MediaAssetStatus;
import com.marketplace.shared.api.MediaLookupPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * L38 (realestate systems plan — listing completeness score): the media
 * module's implementation of the {@link MediaLookupPort} cross-module read
 * contract. A read-only delegation to a derived count query — the sibling
 * of {@code MediaExportAdapter} in this package (the export contract's
 * adapter; this one is the completeness computation's).
 *
 * <p>The status filter is fixed to {@link MediaAssetStatus#UPLOADED} here
 * — the port's documented contract: only storage-verified objects count
 * toward the listing's photo completeness. Soft-deleted rows are excluded
 * by the shared {@code @SoftDelete} on every derived query.
 */
@Component
@Transactional(readOnly = true)
public class MediaLookupAdapter implements MediaLookupPort {

    private final MediaAssetRepository mediaAssetRepository;

    public MediaLookupAdapter(MediaAssetRepository mediaAssetRepository) {
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Override
    public long countUploadedByListing(UUID listingId) {
        return mediaAssetRepository.countByListingIdAndStatus(listingId, MediaAssetStatus.UPLOADED);
    }
}
