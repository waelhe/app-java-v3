package com.marketplace.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaAssetStatusTest {

    @Test
    void pendingUploadCanBecomeUploaded() {
        assertDoesNotThrow(() -> MediaAssetStatus.PENDING_UPLOAD.validateTransitionTo(MediaAssetStatus.UPLOADED));
    }

    @Test
    void uploadedIsTerminal() {
        assertThrows(com.marketplace.shared.api.ConflictException.class,
                () -> MediaAssetStatus.UPLOADED.validateTransitionTo(MediaAssetStatus.UPLOADED));
    }
}
