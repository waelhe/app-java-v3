package com.marketplace.media;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3 closure guard: pins the thumbnail failure counter's contract — the
 * meter name (house {@code marketplace.*} convention for directly-registered
 * custom meters), the {@code reason} tag, and the bounded four-value
 * vocabulary measured from the pipeline's failure surfaces. The name and
 * tags are the operational interface; a silent rename here would break the
 * D3 dashboards/queries the counter exists for, so they are pinned by test.
 */
class MediaThumbnailMetricsTest {

    @Test
    @DisplayName("the counter name and reason tag follow the house meter convention and are pinned")
    void counterNameAndTagArePinned() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MediaThumbnailMetrics metrics = new MediaThumbnailMetrics(registry);

        metrics.incrementFailure(MediaThumbnailMetrics.FailureReason.FETCH);

        assertThat(registry.getMeters()).hasSize(1);
        var counter = registry.get(MediaThumbnailMetrics.FAILURE_COUNTER).counter();
        assertThat(counter.getId().getName()).isEqualTo("marketplace.media.thumbnail.failure");
        assertThat(counter.getId().getTag("reason")).isEqualTo("storage-fetch");
        assertThat(counter.getId().getDescription()).contains("D3");
    }

    @Test
    @DisplayName("every failure reason registers its own tagged counter and counts independently")
    void eachReasonCountsIndependently() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MediaThumbnailMetrics metrics = new MediaThumbnailMetrics(registry);

        metrics.incrementFailure(MediaThumbnailMetrics.FailureReason.FETCH);
        metrics.incrementFailure(MediaThumbnailMetrics.FailureReason.FETCH);
        metrics.incrementFailure(MediaThumbnailMetrics.FailureReason.DECODE);
        metrics.incrementFailure(MediaThumbnailMetrics.FailureReason.ENCODE);
        metrics.incrementFailure(MediaThumbnailMetrics.FailureReason.STORE);

        assertThat(registry.get(MediaThumbnailMetrics.FAILURE_COUNTER)
                .tag(MediaThumbnailMetrics.REASON_TAG, "storage-fetch").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(MediaThumbnailMetrics.FAILURE_COUNTER)
                .tag(MediaThumbnailMetrics.REASON_TAG, "decode").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MediaThumbnailMetrics.FAILURE_COUNTER)
                .tag(MediaThumbnailMetrics.REASON_TAG, "encode").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MediaThumbnailMetrics.FAILURE_COUNTER)
                .tag(MediaThumbnailMetrics.REASON_TAG, "store").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the reason vocabulary is closed: exactly the four measured failure surfaces")
    void reasonVocabularyIsClosed() {
        assertThat(MediaThumbnailMetrics.FailureReason.values()).hasSize(4);
        assertThat(MediaThumbnailMetrics.FailureReason.values())
                .containsExactly(MediaThumbnailMetrics.FailureReason.FETCH,
                        MediaThumbnailMetrics.FailureReason.DECODE,
                        MediaThumbnailMetrics.FailureReason.ENCODE,
                        MediaThumbnailMetrics.FailureReason.STORE);
    }
}
