package com.marketplace.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The 2026-11-06 #809 round (Major · Functional Correctness): the abandoned
 * pending-upload reaper. {@code requestUpload} answers a presigned PUT URL
 * whose lifetime is {@code presignTtl}, but the PENDING row it allocates
 * has NO bounded lifetime of its own — and no owner-facing surface ever
 * lists the pending rows, so an owner who loses the asset ids (or never
 * finishes the upload at all) leaves rows that occupy cap slots FOREVER:
 * {@code requestProductUpload} eventually refuses every new upload with
 * the cap's classified error while nothing displayable exists to delete.
 *
 * <p><b>The horizon:</b> a row is abandoned only once it is older than
 * {@code presignTtl} PLUS a grace window (default 24h,
 * {@code marketplace.media.pending-upload-expiry-grace-hours}). The grace
 * is deliberate: the presigned URL's expiry is a URL-lifetime fact, not a
 * row-lifetime fact — {@code confirmUpload} verifies the object through
 * {@code verifyUploaded} and never consults the row's age, so an object
 * genuinely uploaded before the URL expired may still be confirmed some
 * time after it; the grace window keeps that confirmation legal while the
 * reaper stays the ONE authority that frees the slot. The reviewer's
 * exact demand: presign expiry alone must never exclude a row from the
 * cap — only this job's expiry does (the live count excludes EXPIRED,
 * never PENDING).</p>
 *
 * <p><b>The transition is the un-confirmability:</b> the reaper moves the
 * row to the terminal {@link MediaAssetStatus#EXPIRED} through the state
 * machine's own law ({@link MediaAsset#expirePending}), after which
 * {@code confirmUpload}'s PENDING_UPLOAD gate refuses it — and the object
 * deletion is best-effort BEFORE the transition commits (an absent object
 * — the upload never happened — is the common case; a storage failure
 * logs and skips the row, never throws the scheduled run away).</p>
 *
 * <p>Spring Boot's own scheduling model (docs.spring.io/spring-boot — Task
 * Execution and Scheduling): {@code @EnableScheduling} on the application
 * class activates the {@code @Scheduled} methods; the fixed delay keeps
 * runs off the clock-alignment path and never overlaps the previous run
 * (the {@code ConsumedRefreshTokenCleanupJob} precedent verbatim).</p>
 */
@Component
public class PendingUploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PendingUploadCleanupJob.class);

    private final MediaAssetRepository mediaAssetRepository;
    private final ObjectProvider<S3MediaStorage> storage;
    private final MediaProperties properties;
    private final Duration grace;

    public PendingUploadCleanupJob(MediaAssetRepository mediaAssetRepository,
                                   ObjectProvider<S3MediaStorage> storage,
                                   MediaProperties properties,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${marketplace.media.pending-upload-expiry-grace-hours:24}")
                                   long expiryGraceHours) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.storage = storage;
        this.properties = properties;
        this.grace = Duration.ofHours(expiryGraceHours);
    }

    /**
     * One reaping pass: every PENDING row older than presignTtl + grace
     * loses its storage object (best-effort) and reaches the terminal
     * EXPIRED state — un-confirmable forever after, and never again a
     * cap slot.
     */
    @Scheduled(fixedDelayString = "${marketplace.media.pending-upload-cleanup-interval-ms:3600000}")
    @Transactional
    public void reapAbandonedPendingUploads() {
        Instant cutoff = Instant.now().minus(properties.limits().presignTtl()).minus(grace);
        List<MediaAsset> abandoned = mediaAssetRepository
                .findByStatusAndCreatedAtBefore(MediaAssetStatus.PENDING_UPLOAD, cutoff);
        if (abandoned.isEmpty()) {
            return;
        }
        S3MediaStorage s3 = storage.getIfAvailable();
        int reaped = 0;
        for (MediaAsset asset : abandoned) {
            if (s3 != null) {
                try {
                    s3.deleteObject(asset.getObjectKey());
                } catch (RuntimeException storageFailure) {
                    // Best-effort by contract: a storage outage must never
                    // abort the reaping run (the next pass retries the
                    // object) — but the row is NOT expired past a failed
                    // deletion either, so an un-deleted object never leaks
                    // past a live row that could still be confirmed.
                    log.warn("pending-upload reaper could not delete the object {} — row {} stays PENDING",
                            asset.getObjectKey(), asset.getId());
                    continue;
                }
            }
            asset.expirePending();
            reaped++;
        }
        // The repository flushes with the transaction; the count is the
        // run's own observability line (the scheduled-run precedent).
        log.info("reaped {} abandoned pending upload(s) past the presign+grace horizon", reaped);
    }
}
