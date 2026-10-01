package com.marketplace.media;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaAssetTest {

    @Test
    void createStartsPendingUpload() {
        MediaAsset asset = MediaAsset.create(
                UUID.randomUUID(), UUID.randomUUID(),
                "listings/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 1024L, 1);

        assertEquals(MediaAssetStatus.PENDING_UPLOAD, asset.getStatus());
        assertEquals("image/jpeg", asset.getContentType());
        assertEquals(1024L, asset.getSizeBytes());
        assertEquals(1, asset.getPosition());
    }

    @Test
    void markUploadedMovesStateOnce() {
        MediaAsset asset = MediaAsset.create(
                UUID.randomUUID(), UUID.randomUUID(), "k", "image/png", 5L, 1);

        asset.markUploaded();
        assertEquals(MediaAssetStatus.UPLOADED, asset.getStatus());

        assertThrows(com.marketplace.shared.api.ConflictException.class, asset::markUploaded);
    }

    // ---------- L48: the post target (gap #2 — post images) ----------

    /**
     * L48: the two targets' own invariants — a LISTING row carries its
     * listing and no post; a POST row carries its post, its author in
     * providerId (the A1 user-id basis), and no listing (the V76
     * exactly-one-target invariant, held by construction).
     */
    @Test
    void createForPost_isPostKindWithAuthorOwnershipAndNoListing() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        MediaAsset asset = MediaAsset.createForPost(
                postId, authorId,
                "posts/" + postId + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 2048L, 1);

        assertEquals(MediaOwnerKind.POST, asset.getOwnerKind());
        assertEquals(postId, asset.getPostId());
        assertEquals(authorId, asset.getProviderId());
        assertNull(asset.getListingId());
        assertEquals(MediaAssetStatus.PENDING_UPLOAD, asset.getStatus());
        assertEquals(1, asset.getPosition());
    }

    /** The listing factory pins the LISTING kind (the pre-L48 default). */
    @Test
    void create_isListingKindWithNoPost() {
        MediaAsset asset = MediaAsset.create(
                UUID.randomUUID(), UUID.randomUUID(), "k", "image/png", 5L, 1);

        assertEquals(MediaOwnerKind.LISTING, asset.getOwnerKind());
        assertNull(asset.getPostId());
    }
}
