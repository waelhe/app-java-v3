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
        // argThat-inside-when is the documented Mockito trap: registering the
        // NEXT stubbing re-evaluates this stubbing's matchers against the
        // registration invocation, whose argument is argThat's null
        // placeholder — the lambda runs on null and NPEs deterministically
        // (measured on CI: this test never ran before; the reactor unmasked
        // it only after the catalog and pricing fixes landed). The house-safe
        // shape answers from the REAL sql at invocation time — the sibling
        // tests' contains() matchers are null-safe for the same reason.
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("UPDATE provider_services s SET title = ?")) {
                return 3;
            }
            if (sql.startsWith("UPDATE provider_services_aud s SET title = ?")) {
                return 2;
            }
            return 0;
        });
        when(jdbcTemplate.queryForList(
                anyString(), eq(UUID.class), eq(SUBJECT)))
                .thenReturn(List.of());

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        // The services' five rows join the grand total.
        assertThat(purged).isEqualTo(5);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE provider_services s SET title = ?, description = NULL")
                                && sql.contains("FROM provider_profiles p")
                                && sql.contains("s.provider_id = p.id")
                                && sql.contains("p.user_id = ?")
                                && sql.contains("s.title <> ?")
                                && sql.contains("s.description IS NOT NULL")),
                any(Object[].class));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE provider_services_aud s SET title = ?, description = NULL")
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
}
