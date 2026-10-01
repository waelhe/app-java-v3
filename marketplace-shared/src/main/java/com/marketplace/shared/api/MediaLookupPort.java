package com.marketplace.shared.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read port for the media line's cross-module reads (realestate systems
 * plan L38 — listing completeness score; L48 — the neighborhood feed's
 * post media). Implemented by the {@code marketplace-media} module
 * ({@code MediaLookupAdapter}), consumed by the catalog module's
 * completeness computation and the community module's feed read — the
 * exact {@code PropertyDetailsPort} house pattern: the interface lives
 * in shared-api, the data owner implements it, the consumer injects it.
 * No module boundary is crossed in code.
 *
 * <p><b>The UPLOADED-only contract:</b> only assets that reached
 * {@code MediaAssetStatus.UPLOADED} count or ride a read — the storage
 * layer verified the object exists (HeadObject) on that path alone. A
 * {@code PENDING_UPLOAD} row is a presigned URL that was issued but never
 * confirmed; counting it would reward a photo the gallery cannot display.
 *
 * <p><b>Soft delete:</b> {@code MediaAsset} extends the shared
 * {@code BaseEntity} (Hibernate 7 {@code @SoftDelete}), so every derived
 * query — the repository method this adapter delegates to — already
 * excludes purged assets. No extra filter is needed or allowed here.
 *
 * <p><b>Presigned URLs in a read port (L48):</b> the entries carry
 * time-limited presigned GET URLs, computed locally by the media module
 * (no network — the {@code listByListing} stance). The object keys stay
 * the media module's private concern; consumers never see them. The
 * URL TTL is the media module's configured presign lifetime, exactly the
 * listing read's contract.
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

    /**
     * L48 (gap #2 — post images): every UPLOADED media asset of the given
     * posts, in display order within each post — the neighborhood feed's
     * one grouped media read over the page's post ids (the L47 reactions
     * pattern: one IN query, no per-post reads). Soft-deleted assets are
     * excluded by the shared soft-delete mechanism; PENDING_UPLOAD assets
     * never ride the read. An empty or null-safe empty {@code postIds}
     * collection answers an empty list — a closed feed costs nothing.
     *
     * @param postIds the community posts whose media the feed row carries
     * @return the flat list ordered by (postId, position); each entry
     *         carries its own {@code postId} so the consumer groups
     *         without a second assumption
     */
    List<PostMediaEntry> findUploadedByPostIds(Collection<UUID> postIds);

    /**
     * L48: one feed-visible media asset of a post. Immutable value object
     * — no behaviour. The URLs are freshly presigned GETs (the original
     * plus the deterministic L28 thumbnail when processing has run; the
     * thumbnail link is {@code null} until then and clients fall back to
     * the original — the listing read's own contract).
     *
     * <p>Deliberately no {@code objectKey}, {@code status} or storage
     * metadata: the consumer renders, the media module owns storage.
     */
    record PostMediaEntry(
            UUID postId,
            UUID mediaId,
            String url,
            String thumbUrl,
            String contentType,
            int position
    ) {
    }
}
