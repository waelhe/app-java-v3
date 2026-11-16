package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L28 (feature-expansion roadmap §5): published by the media module after an
 * upload is confirmed (storage-verified) and committed. Carries the asset id
 * only — the listener resolves the row, exactly like the other domain events
 * ({@code BookingCreatedEvent} house pattern).
 *
 * <p>Consumed by the media module's own thumbnail listener via the standard
 * Modulith dispatch ({@code @ApplicationModuleListener}: AFTER_COMMIT, async,
 * REQUIRES_NEW) — so a processing failure marks the publication FAILED and the
 * documented resubmission machinery retries it (roadmap debt D3 semantics),
 * while the upload itself stays UPLOADED and the confirm call already
 * returned.
 */
public record MediaUploadedEvent(UUID mediaId) {
}
