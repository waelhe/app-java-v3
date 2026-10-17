package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Phase 6 (the first composite journey's feed leg): one photo of a
 * neighborhood feed post as the home composite's read carries it — the
 * L48 projection discipline verbatim: exactly what the feed card renders
 * (a display URL pair and the position order), mapped from the media
 * module's presigned entry at the feed read; the object key, the status
 * and every storage fact stay the media module's private concern.
 *
 * <p>The shared twin of the community module's internal
 * {@code PostMediaView} — the cross-module transport shape for the
 * framework-neutral feed port (the {@code PostLookupAdapter} house
 * pattern: shared views cross, owner internals never do).</p>
 *
 * @param mediaId     the media module's id of the photo
 * @param url         the time-limited presigned GET URL the media module
 *                    computed for THIS read (always present — the point
 *                    of the entry)
 * @param thumbUrl    null until background processing has run (the L28
 *                    contract — clients fall back to {@code url}), equal
 *                    to {@code url} when the original is its own
 *                    thumbnail by design
 * @param contentType the photo's content type (e.g. {@code image/jpeg})
 * @param position    the photo's position order within the post
 */
public record NeighborhoodFeedMediaView(
        UUID mediaId,
        String url,
        String thumbUrl,
        String contentType,
        int position
) {
}
