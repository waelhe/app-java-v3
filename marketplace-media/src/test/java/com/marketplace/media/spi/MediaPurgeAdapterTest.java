package com.marketplace.media.spi;

import com.marketplace.media.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G3 (adversarial review 2026-11-10, owner-gate batch 6a — the documented
 * recommendation's media purge, both degrees): the media module's
 * {@link com.marketplace.shared.api.AuthoredContentPurgePort} adapter's
 * contract proofs. The subject's rows tombstone to PURGED (Degree 1 — a
 * state no read rail presigns from: every rail filters
 * {@code status = 'UPLOADED'}), the subject's exact object keys
 * (originals + thumbnails) ride the batch destruction seam
 * (Degree 2 — the objects themselves), and the re-run is the exact-zero
 * idempotence the port's contract carries.
 */
@ExtendWith(MockitoExtension.class)
class MediaPurgeAdapterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private MediaService mediaService;

    @Test
    void tombstonesTheSubjectRowsAndQueuesTheExactObjectKeysForDestruction() throws Exception {
        // Degree 1 + Degree 2: the keys are collected on the SAME
        // <> PURGED predicate the tombstone statements filter on, then the
        // batch destruction receives exactly them (originals + thumbnails +
        // review media keys). The read side is driven EXPLICITLY: the
        // captured RowCallbackHandler is fed one fully-stubbed ResultSet
        // per read (a deterministic hand-rolled row set — the read's own
        // contract, no Mockito answer indirection).
        UUID subject = UUID.randomUUID();
        when(jdbcTemplate.query(anyString(), any(RowCallbackHandler.class), eq(subject)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    RowCallbackHandler handler = inv.getArgument(1);
                    if (sql.contains("media_assets")) {
                        handler.processRow(resultSet(
                                new String[]{"listings/1/a.jpg", "listings/1/a.jpg/thumb"},
                                new String[]{"posts/2/b.jpg", null}));
                    } else {
                        handler.processRow(resultSet(new String[]{"review-media/3/c.jpg"}));
                    }
                    return null;
                });
        when(jdbcTemplate.update(anyString(), eq(subject))).thenReturn(2, 1);

        int purged = new MediaPurgeAdapter(jdbcTemplate, mediaService).purgeAuthoredTexts(subject);

        assertThat(purged).isEqualTo(3);
        // Degree 1: the tombstone statements — the version advance rides the
        // same statement (the B5 family law).
        verify(jdbcTemplate).update(
                "UPDATE media_assets SET status = 'PURGED', version = version + 1 "
                        + "WHERE provider_id = ? AND status <> 'PURGED'",
                subject);
        verify(jdbcTemplate).update(
                "UPDATE review_media SET status = 'PURGED', version = version + 1 "
                        + "WHERE uploader_id = ? AND status <> 'PURGED'",
                subject);
        // Degree 2: the exact keys — the originals, the thumbnails that
        // exist, and the review media objects.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass((Class) List.class);
        verify(mediaService).destroyObjectsAfterCommit(keys.capture());
        assertThat((List<String>) keys.getValue())
                .containsExactlyInAnyOrder("listings/1/a.jpg", "listings/1/a.jpg/thumb", "posts/2/b.jpg",
                        "review-media/3/c.jpg");
    }

    @Test
    void theReRunOnAnAlreadyPurgedSubjectIsTheExactZeroNoOp() throws Exception {
        // The port's idempotence contract: the key collection reads the same
        // <> PURGED predicate the tombstones filter on — an already-purged
        // subject selects no keys, matches no rows, destroys nothing.
        UUID subject = UUID.randomUUID();
        doAnswer(inv -> null)
                .when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), eq(subject));
        when(jdbcTemplate.update(anyString(), eq(subject))).thenReturn(0, 0);

        int purged = new MediaPurgeAdapter(jdbcTemplate, mediaService).purgeAuthoredTexts(subject);

        assertThat(purged).isZero();
        verifyNoInteractions(mediaService);
    }

    @Test
    void aSubjectWithNoMediaDestroysNothing() throws Exception {
        UUID subject = UUID.randomUUID();
        doAnswer(inv -> null)
                .when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), eq(subject));
        when(jdbcTemplate.update(anyString(), eq(subject))).thenReturn(0, 0);

        new MediaPurgeAdapter(jdbcTemplate, mediaService).purgeAuthoredTexts(subject);

        verify(mediaService, never()).destroyObjectsAfterCommit(any());
    }

    /**
     * A fully-stubbed sequential ResultSet for one read's rows: the
     * consecutive-value form stubs getString(1)/(2) with one value per row
     * (Mockito's documented consecutive-returns), and next() walks a plain
     * cursor — every stub exists BEFORE the handler's first call, so the
     * read is deterministic with zero answer indirection (the CI-measured
     * NPE class the nested-answer form produced).
     */
    private static ResultSet resultSet(String[]... rows) throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        // next(): true once per row, then false — built as an explicit
        // Boolean sequence (the consecutive-value form's own rule).
        Boolean[] sequence = new Boolean[rows.length + 1];
        java.util.Arrays.fill(sequence, true);
        sequence[rows.length] = false;
        org.mockito.Mockito.when(rs.next()).thenReturn(sequence[0], java.util.Arrays.copyOfRange(sequence, 1, sequence.length));
        org.mockito.Mockito.when(rs.getString(1)).thenReturn(
                java.util.Arrays.stream(rows).map(r -> r[0]).toArray(String[]::new));
        org.mockito.Mockito.when(rs.getString(2)).thenReturn(
                java.util.Arrays.stream(rows).map(r -> r.length > 1 ? r[1] : null).toArray(String[]::new));
        return rs;
    }

}
