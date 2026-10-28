package com.marketplace.provider.spi;

import java.util.List;
import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.CacheInvalidationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row 31 (agent findings 2026-10-26 — CWE-212): the provider content
 * purge must cover the offered-service text — the public page's services
 * block is the provider's own display statement, attributed to the
 * OWNING PROFILE (the reliable authorship for delegated edits), never to
 * the typist. The statement-shape contract under test:
 *
 * <ul>
 *   <li>both service statements (base + Envers mirror) join through
 *       {@code provider_profiles.user_id} — the profile's ownership is
 *       the authorship;</li>
 *   <li>the required title takes the shared tombstone marker (V96 NOT
 *       NULL) while the nullable description clears to NULL — the bio's
 *       own storage convention — and the operational fields stay out of
 *       the statement entirely;</li>
 *   <li>the idempotence filter keeps the port's exact-counts contract —
 *       an already-purged service matches nothing on a re-run.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProviderProfileContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private ProviderProfileContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ProviderProfileContentPurgeAdapter(jdbcTemplate, eventPublisher);
    }

    @Test
    void purgeAuthoredTexts_scrubsTheOfferedServiceTextThroughTheOwningProfile() {
        // CI round 3 (measured on 916d155): the stubs use Mockito's built-in
        // startsWith matcher — a null-safe TypeSafeMatcher — instead of
        // custom argThat lambdas. Stacked when() stubbings on the same
        // method make findAnswerFor evaluate the most recent stub against
        // the NEXT stubbing's placeholder invocation, and a custom lambda
        // dereferences that null placeholder (the NPE at line 65, twice
        // measured). Built-in matchers answer false on null by contract.
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.update(
                startsWith("UPDATE provider_services s SET title = ?"),
                any(Object[].class)))
                .thenReturn(3);
        when(jdbcTemplate.update(
                startsWith("UPDATE provider_services_aud s SET title = ?"),
                any(Object[].class)))
                .thenReturn(2);
        when(jdbcTemplate.queryForList(
                anyString(), eq(UUID.class), eq(SUBJECT)))
                .thenReturn(List.of());

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        // The services' five rows join the grand total.
        assertThat(purged).isEqualTo(5);
        // Review round 1: the BASE-table statements must bump the
        // optimistic-lock version (OfferedService inherits @Version) — a
        // purge a stale managed entity can silently revert is not a
        // purge. The Envers mirror carries no version column and must
        // stay untouched by the bump.
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE provider_services s SET title = ?, description = NULL")
                                // Review round 2: the increment is qualified s.version — BOTH
                                // joined tables carry a version column, so the unqualified
                                // reference is ambiguous in PostgreSQL and the statement errors
                                // at runtime (column "version" does not exist / is ambiguous).
                                && sql.contains("version = s.version + 1")
                                && sql.contains("FROM provider_profiles p")
                                && sql.contains("s.provider_id = p.id")
                                && sql.contains("p.user_id = ?")
                                && sql.contains("s.title <> ?")
                                && sql.contains("s.description IS NOT NULL")),
                any(Object[].class));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE provider_services_aud s SET title = ?, description = NULL")
                                && !sql.contains("version")
                                && sql.contains("FROM provider_profiles p")),
                any(Object[].class));
    }

    @Test
    void purgeAuthoredTexts_stillPurgesTheProfilePersonaAndEvictsItsCache() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        UUID profileId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(SUBJECT)))
                .thenReturn(List.of(profileId));

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        // Eight statements: the persona's six (bio + display_name +
        // agency/license on base and mirror) plus the services' two —
        // each counting its own purged rows.
        assertThat(purged).isEqualTo(8);
        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(8))
                .update(anyString(), any(Object[].class));
        verify(eventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.any(CacheInvalidationRequested.class));
    }

    @Test
    void purgeAuthoredTexts_bumpsTheOptimisticLockVersionOnEveryBaseTableStatement() {
        // Review round 1: ProviderProfile inherits @Version as well, and
        // provider_profiles.version is NULL-able (V14) — every base-table
        // statement must carry COALESCE(version, 0) + 1 so a stale managed
        // entity cannot flush the persona back over the purge, while the
        // three _aud mirrors stay version-free.
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(SUBJECT)))
                .thenReturn(List.of());

        adapter.purgeAuthoredTexts(SUBJECT);

        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE provider_profiles SET bio = NULL")
                                && sql.contains("version = COALESCE(version, 0) + 1")),
                any(Object[].class));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE provider_profiles SET display_name = ?")
                                && sql.contains("version = COALESCE(version, 0) + 1")),
                any(Object[].class));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE provider_profiles SET agency_name = NULL")
                                && sql.contains("version = COALESCE(version, 0) + 1")),
                any(Object[].class));
        verify(jdbcTemplate, org.mockito.Mockito.times(3)).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE provider_profiles_aud")
                                && !sql.contains("version")),
                any(Object[].class));
    }
}
