package com.marketplace.community.spi;

import com.marketplace.shared.api.CommunityMembershipExportEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * L41 — the export adapter's read shape: the query sees the LEFT
 * memberships too (native SQL rides past Hibernate's soft-delete filter
 * — the b-5 discrimination) and maps the stored facts verbatim. The
 * against-the-real-schema round-trip lives in the integration test; this
 * pin guards the SQL's own predicates (owner scoping, the honest
 * soft-delete visibility, the deterministic order).
 */
@ExtendWith(MockitoExtension.class)
class CommunityExportAdapterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ResultSet rs;

    private UUID userId = UUID.randomUUID();

    @Test
    void exportForOwner_mapsTheStoredFactsIncludingLeftMemberships() throws Exception {
        UUID id = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        Instant memberSince = Instant.parse("2026-09-17T09:30:00Z");
        when(rs.getString("id")).thenReturn(id.toString());
        when(rs.getString("location_id")).thenReturn(locationId.toString());
        when(rs.getString("verification_state")).thenReturn("SELF_DECLARED");
        when(rs.getTimestamp("member_since")).thenReturn(Timestamp.from(memberSince));
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(memberSince));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(memberSince));
        when(rs.getBoolean("is_deleted")).thenReturn(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<CommunityMembershipExportEntry> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<CommunityMembershipExportEntry> entries =
                new CommunityExportAdapter(jdbcTemplate).exportForOwner(userId);

        assertThat(entries).hasSize(1);
        CommunityMembershipExportEntry entry = entries.get(0);
        assertThat(entry.id()).isEqualTo(id);
        assertThat(entry.locationId()).isEqualTo(locationId);
        assertThat(entry.verificationState()).isEqualTo("SELF_DECLARED");
        assertThat(entry.memberSince()).isEqualTo(memberSince);
        assertThat(entry.deleted()).isTrue();
    }

    @Test
    void exportForOwner_scopesToTheOwnerWithTheHonestOrder() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(userId)))
                .thenReturn(List.of());

        new CommunityExportAdapter(jdbcTemplate).exportForOwner(userId);

        org.mockito.Mockito.verify(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.contains("WHERE user_id = ?")
                                && sql.contains("ORDER BY created_at, id")
                                && !sql.contains("is_deleted = FALSE")),
                any(RowMapper.class), eq(userId));
    }
}
