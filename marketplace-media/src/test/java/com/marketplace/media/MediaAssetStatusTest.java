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

    // ---- G3 (owner-gate batch 6a — the erasure tombstone) ----

    @Test
    void g3_purgedIsTerminalAndNeverReachedThroughTheStateMachine() {
        // The tombstone is written ONLY by the purge adapter's native SQL
        // (the administrative erasure overwrite, the AuthoredContentPurgePort
        // family discipline) — the state machine never enters it and never
        // leaves it: every transition from PURGED is refused.
        assertThrows(com.marketplace.shared.api.ConflictException.class,
                () -> MediaAssetStatus.PURGED.validateTransitionTo(MediaAssetStatus.UPLOADED));
        assertThrows(com.marketplace.shared.api.ConflictException.class,
                () -> MediaAssetStatus.PURGED.validateTransitionTo(MediaAssetStatus.PURGED));
        assertThrows(com.marketplace.shared.api.ConflictException.class,
                () -> MediaAssetStatus.UPLOADED.validateTransitionTo(MediaAssetStatus.PURGED));
    }
}
