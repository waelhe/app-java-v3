package com.marketplace.media.spi;

import com.marketplace.media.MediaAssetRepository;
import com.marketplace.media.MediaAssetStatus;
import com.marketplace.media.MediaService;
import com.marketplace.shared.api.MediaLookupPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
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
 *
 * <p><b>L48 (gap #2 — post images):</b> the port widened with the feed's
 * grouped post-media read. The presigned URLs are computed by
 * {@link MediaService#listByPostIds} (presigning is the service's — the
 * storage object is this module's private concern, the consumer never
 * sees a key); this adapter stays the pure delegation seam the house
 * pattern pins.
 */
@Component
@Transactional(readOnly = true)
public class MediaLookupAdapter implements MediaLookupPort {

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaService mediaService;

    public MediaLookupAdapter(MediaAssetRepository mediaAssetRepository,
                              MediaService mediaService) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.mediaService = mediaService;
    }

    @Override
    public long countUploadedByListing(UUID listingId) {
        return mediaAssetRepository.countByListingIdAndStatus(listingId, MediaAssetStatus.UPLOADED);
    }

    @Override
    public List<PostMediaEntry> findUploadedByPostIds(Collection<UUID> postIds) {
        return mediaService.listByPostIds(postIds);
    }
}
