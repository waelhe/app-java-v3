package com.marketplace.media.spi;

import com.marketplace.media.MediaAsset;
import com.marketplace.media.MediaAssetRepository;
import com.marketplace.shared.api.MediaExportEntry;
import com.marketplace.shared.api.MediaExportPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the media module's implementation of the
 * {@link MediaExportPort} cross-module contract. A read-only delegation
 * with the plan's provenance mapping: "وصف وسائطه" — descriptive metadata
 * of the requester's assets, never file bytes. The derived thumbnail key
 * is deliberately absent (a system-derived artifact, not his provided
 * data).
 *
 * <p>Ownership basis (the measured A1 fact): {@code media_assets.provider_id}
 * is a user id (V2/V32) — the requester's assets resolve by that column
 * directly.
 */
@Component
@Transactional(readOnly = true)
public class MediaExportAdapter implements MediaExportPort {

    private final MediaAssetRepository mediaAssetRepository;

    public MediaExportAdapter(MediaAssetRepository mediaAssetRepository) {
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Override
    public List<MediaExportEntry> exportForOwner(UUID userId) {
        return mediaAssetRepository.findAllByProviderIdOrderByCreatedAtAsc(userId)
                .stream()
                .map(MediaExportAdapter::toEntry)
                .toList();
    }

    private static MediaExportEntry toEntry(MediaAsset asset) {
        return new MediaExportEntry(
                asset.getId(),
                asset.getListingId(),
                asset.getObjectKey(),
                asset.getContentType(),
                asset.getSizeBytes(),
                asset.getStatus().name(),
                asset.getPosition(),
                asset.getCreatedAt(),
                asset.getUpdatedAt());
    }
}
