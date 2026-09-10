package com.marketplace.media.spi;

import com.marketplace.media.MediaAsset;
import com.marketplace.media.MediaAssetRepository;
import com.marketplace.shared.api.MediaExportEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaExportAdapterTest {

    private final MediaAssetRepository mediaAssetRepository = mock(MediaAssetRepository.class);
    private final MediaExportAdapter adapter = new MediaExportAdapter(mediaAssetRepository);

    @Test
    void exportsDescriptiveMetadataNeverTheThumbnailKey() {
        UUID me = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        // The public entity factory leaves thumb_object_key null (the
        // background processor's own artifact — never the requester's
        // provided data, so it is absent from the entry contract).
        MediaAsset asset = new MediaAsset(UUID.randomUUID(), listing, me,
                "listings/" + listing + "/photo.jpg", "image/jpeg", 2048L, 1);
        when(mediaAssetRepository.findAllByProviderIdOrderByCreatedAtAsc(me))
                .thenReturn(List.of(asset));

        List<MediaExportEntry> entries = adapter.exportForOwner(me);

        assertEquals(1, entries.size());
        MediaExportEntry entry = entries.get(0);
        assertEquals(asset.getId(), entry.id());
        assertEquals(listing, entry.listingId());
        assertEquals("listings/" + listing + "/photo.jpg", entry.objectKey());
        assertEquals("image/jpeg", entry.contentType());
        assertEquals(2048L, entry.sizeBytes());
        assertEquals("PENDING_UPLOAD", entry.status());
        assertEquals(1, entry.position());
        assertNull(entry.createdAt());
        // The metadata-only contract: 9 fields, none of them file bytes and
        // none of them the derived thumbnail key.
        assertEquals(9, MediaExportEntry.class.getRecordComponents().length);
    }
}
