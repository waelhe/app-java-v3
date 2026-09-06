package com.marketplace.media;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findByListingIdAndStatusOrderByPositionAsc(UUID listingId, MediaAssetStatus status);

    List<MediaAsset> findByListingIdOrderByPositionAsc(UUID listingId);

    long countByListingId(UUID listingId);
}
