package com.marketplace.community;

import com.marketplace.shared.api.MediaLookupPort;

import java.util.UUID;

/**
 * L48 (the Nextdoor-2026 completeness wave — gap #2, post images): the
 * feed row's own media read model — one photo of a post, as the
 * neighborhood feed renders it. The community layer's projection
 * discipline holds: the entry carries exactly what the feed card
 * renders (a display URL pair and the position order), mapped from the
 * media module's port entry at the feed read; the object key, the
 * status and every storage fact stay the media module's private
 * concern.
 *
 * <p>{@code thumbUrl} follows the L28 contract: null until background
 * processing has run (clients fall back to {@code url}), equal to
 * {@code url} when the original is its own thumbnail by design. Both
 * URLs are time-limited presigned GETs the media module computed for
 * THIS feed read — the same freshness stance the listing read carries.
 */
public record PostMediaView(
        UUID mediaId,
        String url,
        String thumbUrl,
        String contentType,
        int position
) {
    static PostMediaView of(MediaLookupPort.PostMediaEntry entry) {
        return new PostMediaView(
                entry.mediaId(),
                entry.url(),
                entry.thumbUrl(),
                entry.contentType(),
                entry.position());
    }
}
