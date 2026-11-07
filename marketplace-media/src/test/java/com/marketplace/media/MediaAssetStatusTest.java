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

    // The 2026-11-06 #809 round: the reaper's terminal state — reachable
    // only from PENDING_UPLOAD (the system's expiry), and terminal exactly
    // like UPLOADED (confirmUpload can never revive an abandoned upload).
    @Test
    void pendingUploadCanBeExpiredByTheSystem() {
        assertDoesNotThrow(() -> MediaAssetStatus.PENDING_UPLOAD.validateTransitionTo(MediaAssetStatus.EXPIRED));
    }

    @Test
    void expiredIsTerminal() {
        assertThrows(com.marketplace.shared.api.ConflictException.class,
                () -> MediaAssetStatus.EXPIRED.validateTransitionTo(MediaAssetStatus.UPLOADED));
        assertThrows(com.marketplace.shared.api.ConflictException.class,
                () -> MediaAssetStatus.EXPIRED.validateTransitionTo(MediaAssetStatus.EXPIRED));
    }
}
