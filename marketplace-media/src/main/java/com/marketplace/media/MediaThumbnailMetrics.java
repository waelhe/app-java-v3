package com.marketplace.media;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * D3 closure (roadmap §8, internal-free-work-plan §4): counts thumbnail
 * processing failures by their measured source. Until this counter, a
 * failed processing attempt was only visible through the event-publication
 * registry (the publication goes FAILED and the documented resubmission
 * machinery retries it) — nothing aggregated HOW the pipeline was failing.
 * The counter changes none of that behavior: every increment is followed by
 * the exception propagating, so the FAILED marking and the framework-owned
 * retry (debt D3's semantics, PR #210/#263) are untouched.
 *
 * <p>House pattern: {@code CacheInvalidationMetrics} — a small component
 * around {@code MeterRegistry} with one tagged counter. The meter name
 * follows the house convention of prefixing every directly-registered
 * custom meter with {@code marketplace.} (measured:
 * {@code marketplace.cache.invalidation.evict.failure},
 * {@code marketplace.eventbus.stale}, {@code marketplace.bookings.*}).
 *
 * <p>Reason vocabulary (bounded by {@link FailureReason} — Micrometer tag
 * guidance: keep tag values a closed set): the pipeline's four measurable
 * failure surfaces at the service boundary. The roadmap plan listed four
 * documented risk areas from the L28 hardening history; two of them are
 * <b>by-design non-failures</b> after the CodeRabbit #263 hardening and
 * therefore deliberately have no counter value:
 * <ul>
 *   <li>raster budget — a source declaring more pixels than
 *       {@code thumbSourceMaxPixels} keeps the original as its own
 *       thumbnail (never decoded — a success path, not a failure);</li>
 *   <li>target image type — always one of the two explicit standard types
 *       chosen from {@code hasAlpha()}, so the TYPE_CUSTOM
 *       IllegalArgumentException the hardening removed cannot occur.</li>
 * </ul>
 */
@Component
public class MediaThumbnailMetrics {

    /** Counted per failure attempt — a retry that fails again counts again. */
    static final String FAILURE_COUNTER = "marketplace.media.thumbnail.failure";

    static final String REASON_TAG = "reason";

    /**
     * The pipeline's measured failure surfaces (code facts, see
     * {@code MediaService.processThumbnail}):
     * <ul>
     *   <li>{@link #FETCH} — reading the original object from storage threw
     *       (AWS SDK unchecked channel; transient — the retry can fix it);</li>
     *   <li>{@link #DECODE} — the image bytes failed to read/decode
     *       (IOException from the read half of the image math; typically
     *       permanent — corrupt bytes — so the 24h resubmission will keep
     *       failing, which is exactly the signal D3 wanted visible);</li>
     *   <li>{@link #ENCODE} — the scaled image failed to re-encode
     *       ({@link ThumbnailEncodingException} from the write half; a
     *       realistic case is a declared-content-type/actual-bytes
     *       mismatch — PNG-with-alpha bytes under a jpeg declaration die
     *       at the JPEG writer with "Bogus input colorspace", measured);</li>
     *   <li>{@link #STORE} — writing the thumbnail object to storage threw
     *       (transient — the retry can fix it).</li>
     * </ul>
     */
    public enum FailureReason {
        FETCH("storage-fetch"),
        DECODE("decode"),
        ENCODE("encode"),
        STORE("store");

        private final String tagValue;

        FailureReason(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    private final MeterRegistry meterRegistry;

    public MediaThumbnailMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments the failure counter for the given source. Callers must
     * rethrow the original exception afterwards — this method never
     * swallows, the FAILED marking and retry machinery stay in charge.
     */
    public void incrementFailure(FailureReason reason) {
        Counter.builder(FAILURE_COUNTER)
                .description("Thumbnail processing failures by source — the publication goes FAILED "
                        + "and the framework resubmission machinery owns the retry (debt D3)")
                .tag(REASON_TAG, reason.tagValue)
                .register(meterRegistry)
                .increment();
    }
}
