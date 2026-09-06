package com.marketplace.media;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
