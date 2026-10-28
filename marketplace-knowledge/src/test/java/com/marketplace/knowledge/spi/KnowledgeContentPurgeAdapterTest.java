package com.marketplace.knowledge.spi;

import java.util.List;
import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.KnowledgeEntryWithdrawnEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row 36 (agent findings 2026-10-26): the knowledge purge adapter's
 * statement and signal contract:
 *
 * <ul>
 *   <li>the title and body purge on the base table AND the Envers mirror,
 *       keyed by {@code author_id}, with the marker-guard idempotence
 *       filter — and NO is_deleted filter anywhere (a withdrawn entry's
 *       text purges exactly like a live one's);</li>
 *   <li>every actually-purged entry rides the SAME withdrawal signal the
 *       author's own withdraw publishes ({@code
 *       KnowledgeEntryWithdrawnEvent}) — the AI index's durable
 *       retirement, so completed erasure covers downstream discovery;</li>
 *   <li>an empty purge publishes NOTHING (the re-run's zero rows are the
 *       port's own idempotence contract).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private KnowledgeContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new KnowledgeContentPurgeAdapter(jdbcTemplate, eventPublisher);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgeAuthoredTexts_scrubsTitleAndBody_baseAndMirrorWithoutAnySoftDeleteFilter() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        assertThat(purged).isEqualTo(4);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE knowledge_entries SET title = ?, body = ?")
                                && sql.contains("WHERE author_id = ?")
                                && sql.contains("title <> ?")
                                && sql.contains("body <> ?")
                                && !sql.contains("is_deleted")),
                any(Object[].class));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE knowledge_entries_aud SET title = ?, body = ?")),
                any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgeAuthoredTexts_publishesTheWithdrawalWithTheEntryIdentityForEveryPurgedEntry() {
        UUID entryId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        java.sql.ResultSet row = mock(java.sql.ResultSet.class);
        try {
            when(row.getObject("id", UUID.class)).thenReturn(entryId);
            when(row.getObject("location_id", UUID.class)).thenReturn(locationId);
        } catch (java.sql.SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        assertThat(purged).isEqualTo(2);
        ArgumentCaptor<KnowledgeEntryWithdrawnEvent> event =
                ArgumentCaptor.forClass(KnowledgeEntryWithdrawnEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().entryId()).isEqualTo(entryId);
        assertThat(event.getValue().locationId()).isEqualTo(locationId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgeAuthoredTexts_publishesNothingWhenNothingCarriesContent() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        assertThat(purged).isZero();
        verify(eventPublisher, never()).publishEvent(any(KnowledgeEntryWithdrawnEvent.class));
    }
}
