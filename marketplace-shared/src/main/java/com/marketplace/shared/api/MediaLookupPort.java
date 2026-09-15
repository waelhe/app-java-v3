package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Read port for a listing's media (realestate systems plan L38 — listing
 * completeness score). Implemented by the {@code marketplace-media}
 * module ({@code MediaLookupAdapter}), consumed by the catalog module's
 * completeness computation — the exact {@code PropertyDetailsPort} house
 * pattern: the interface lives in shared-api, the data owner implements
 * it, the consumer injects it. No module boundary is crossed in code.
 *
 * <p><b>The UPLOADED-only contract:</b> only assets that reached
 * {@code MediaAssetStatus.UPLOADED} count — the storage layer verified
 * the object exists (HeadObject) on that path alone. A
 * {@code PENDING_UPLOAD} row is a presigned URL that was issued but never
 * confirmed; counting it would reward a photo the gallery cannot display.
 *
 * <p><b>Soft delete:</b> {@code MediaAsset} extends the shared
 * {@code BaseEntity} (Hibernate 7 {@code @SoftDelete}), so every derived
 * query — the repository method this adapter delegates to — already
 * excludes purged assets. No extra filter is needed or allowed here.
 */
public interface MediaLookupPort {

    /**
     * The number of UPLOADED media assets attached to the listing.
     * Soft-deleted (purged) assets are excluded by the shared soft-delete
     * mechanism; PENDING_UPLOAD assets never count.
     *
     * @param listingId the catalog listing the assets are attached to
     * @return the count, {@code 0} when the listing has none
     */
    long countUploadedByListing(UUID listingId);
}
