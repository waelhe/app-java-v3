package com.marketplace.media;

import com.marketplace.shared.api.MediaUploadedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * L28 (feature-expansion roadmap §5): the thumbnail pipeline's dispatch half.
 * The media module listens to its OWN upload-confirmed event — the roadmap's
 * "حدث داخلي" — through the standard Modulith listener, which the framework
 * runs AFTER_COMMIT, async, in a REQUIRES_NEW transaction
 * ({@code @ApplicationModuleListener}, the house pattern of
 * {@code ProviderReviewStatsListener}).
 *
 * <p>Because the publication is registered in the framework's event
 * publication registry, a processing failure marks it FAILED and the
 * documented resubmission machinery retries it (bounded: immediate under 2
 * attempts, then once per 24h per publication) — exactly the retry semantics
 * the roadmap demands for L28 acceptance (2) and records as debt D3. The
 * upload itself already committed: the asset stays UPLOADED no matter what
 * happens here.
 */
@Component
public class MediaThumbnailListener {

    private final MediaService mediaService;

    public MediaThumbnailListener(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @ApplicationModuleListener
    public void onMediaUploaded(MediaUploadedEvent event) {
        mediaService.processThumbnail(event.mediaId());
    }
}
