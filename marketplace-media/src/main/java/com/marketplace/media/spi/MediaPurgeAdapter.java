package com.marketplace.media.spi;

import com.marketplace.media.MediaService;
import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * G3 (adversarial review 2026-11-10, owner-gate batch 6a — the documented
 * recommendation's media purge, both degrees): the media module's
 * implementation of the {@link AuthoredContentPurgePort} contract. The
 * pre-fix state: the account-erasure flow iterated the free-text purge
 * ports only — the subject's media rows stayed UPLOADED and their S3
 * objects stayed live, so every read rail kept renewing presigned
 * download links for the erased subject's images forever (the ledger's
 * own finding G3: «الوسائط بلا ساق محو أصلاً»), and the pseudonymization
 * plan treated media as export-only data (its line 191) with no purge
 * gate anywhere.
 *
 * <p><b>Degree 1 (توقف الإتاحة):</b> the tombstone — the subject's
 * {@code media_assets} (by {@code provider_id}, which carries the
 * author's user id on EVERY owner kind: the listing/product flows store
 * the owning provider's user id, the post flow stores the author's user
 * id directly — the requestPostUpload javadoc's own measured fact) and
 * his {@code review_media} (by {@code uploader_id}, the review author's
 * user id) flip to {@code PURGED}: a state no read rail presigns from
 * (every rail filters {@code status = 'UPLOADED'}), so signed-link
 * renewal stops the moment the tombstone lands. The
 * {@code version = version + 1} advance rides the same statement — the
 * B5 family law every live-table purge statement carries (a writer
 * holding a pre-purge snapshot fails the optimistic check instead of
 * restoring the purged state).</p>
 *
 * <p><b>Degree 2 (إتلاف الكائن):</b> the objects themselves — the
 * subject's exact object keys (originals + thumbnails) are collected
 * BEFORE the tombstone lands, then destroyed through
 * {@link MediaService#destroyObjectsAfterCommit} (the official
 * documented batch API — AWS S3 {@code DeleteObjects} for known keys),
 * best-effort AFTER the purge transaction commits (the #241 discipline:
 * destroying objects first would leave the rows pointing at vanished
 * objects on a rollback).</p>
 *
 * <p><b>Idempotence:</b> every statement filters
 * {@code status <> 'PURGED''} and the key collection reads the same
 * predicate — a re-run on an already-purged subject selects no keys,
 * matches no rows, and destroys nothing. The plan's §7 gradualism: this
 * adapter runs in its own transaction inside the orchestrator's
 * sequence; a partial failure resumes on re-run.</p>
 */
@Component
public class MediaPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(MediaPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;
    private final MediaService mediaService;

    public MediaPurgeAdapter(JdbcTemplate jdbcTemplate, MediaService mediaService) {
        this.jdbcTemplate = jdbcTemplate;
        this.mediaService = mediaService;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        // The keys FIRST (the same <> PURGED predicate the tombstones
        // filter on — an already-purged subject selects nothing here and
        // every statement below matches zero rows: the exact-count
        // idempotence the port's contract carries).
        List<String> objectKeys = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT object_key, thumb_object_key FROM media_assets "
                        + "WHERE provider_id = ? AND status <> 'PURGED'",
                rs -> {
                    objectKeys.add(rs.getString(1));
                    String thumb = rs.getString(2);
                    if (thumb != null) {
                        objectKeys.add(thumb);
                    }
                },
                userId);
        jdbcTemplate.query(
                "SELECT object_key FROM review_media "
                        + "WHERE uploader_id = ? AND status <> 'PURGED'",
                rs -> objectKeys.add(rs.getString(1)),
                userId);

        // Degree 1: the tombstone statements (the version advance rides the
        // same statement — the B5 family law).
        int assets = jdbcTemplate.update(
                "UPDATE media_assets SET status = 'PURGED', version = version + 1 "
                        + "WHERE provider_id = ? AND status <> 'PURGED'",
                userId);
        int reviewMedia = jdbcTemplate.update(
                "UPDATE review_media SET status = 'PURGED', version = version + 1 "
                        + "WHERE uploader_id = ? AND status <> 'PURGED'",
                userId);

        // Degree 2: the objects — best-effort, AFTER this transaction's
        // commit (the #241 discipline the service method itself carries).
        if (!objectKeys.isEmpty()) {
            mediaService.destroyObjectsAfterCommit(List.copyOf(objectKeys));
        }

        log.info("G3 media purge: tombstoned {} media_assets rows and {} review_media rows for the "
                        + "erased subject; {} storage object keys queued for batch destruction",
                assets, reviewMedia, objectKeys.size());
        return assets + reviewMedia;
    }
}
