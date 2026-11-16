package com.marketplace.identity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.marketplace.identity.spi.AuditHistoryPurgeResult;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.jpa.AuditColumnScrubAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I7 Phase 3 (gate b-4 — the audit history purge): the orchestrator's
 * guards. The full end-state (the information_schema discovery, the
 * per-table scrub, the counterparty isolation) is the integration
 * guard's job on real PostgreSQL; here the contract under test is the
 * orchestration itself: the 409 gate, the load-bearing ORDER (closure
 * recovery BEFORE the scrub BEFORE the mirror deletion — deleting first
 * would orphan the closure and leave the original subject alive in the
 * audit columns), the closure's contents, and the re-run zeroes.
 */
class AuditHistoryPurgeServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
            mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final AuditColumnScrubAdapter scrubAdapter = mock(AuditColumnScrubAdapter.class);
    private final AuditHistoryPurgeService service =
            new AuditHistoryPurgeService(userRepository, jdbcTemplate, scrubAdapter);

    private static User pseudonymizedUser(UUID id, String currentSubject) {
        User user = new User(id, "original-subject", "gone@example.com", "Gone", UserRole.CONSUMER);
        user.applyPseudonymization(currentSubject);
        return user;
    }

    @Test
    void unknownUserAnswersNotFoundBeforeAnythingRuns() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.purge(id, "legal demand", "admin-subject"));

        verify(scrubAdapter, never()).scrubAuditColumns(any());
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
    }

    @Test
    void liveAccountIsRejectedWithConflictBeforeAnyStatementRuns() {
        UUID id = UUID.randomUUID();
        User live = new User(id, "live-subject", "live@example.com", "Live", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(live));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.purge(id, "legal demand", "admin-subject"));

        // The guard's contract: the purge completes an erasure flow — the
        // target must already be pseudonymized.
        assertThat(ex.getMessage()).contains("pseudonymized");
        verify(scrubAdapter, never()).scrubAuditColumns(any());
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
    }

    @Test
    @SuppressWarnings("unchecked") // ArgumentCaptor.forClass(Set.class) — the raw class literal is the API's own shape
    void closureIsRecoveredThenScrubbedThenDeleted_theOrderIsLoadBearing() {
        UUID id = UUID.randomUUID();
        User user = pseudonymizedUser(id, "anon-" + "1".repeat(64));
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(id)))
                .thenReturn(List.of("original-subject", "anon-" + "1".repeat(64)));
        when(scrubAdapter.scrubAuditColumns(any())).thenReturn(7);
        when(jdbcTemplate.update(anyString(), (Object) any())).thenReturn(4);

        AuditHistoryPurgeResult result = service.purge(id, "legal demand", "admin-subject");

        assertThat(result.scrubbedRows()).isEqualTo(7);
        assertThat(result.usersAudRowsDeleted()).isEqualTo(4);

        // The closure: the mirror history's subjects united with the
        // current row's subject — the complete identity-string set the
        // scrub receives.
        ArgumentCaptor<Set<String>> closure = ArgumentCaptor.forClass(Set.class);
        verify(scrubAdapter).scrubAuditColumns(closure.capture());
        assertThat(closure.getValue())
                .containsExactlyInAnyOrder("original-subject", "anon-" + "1".repeat(64));

        // The order is the design: recovery (queryForList) → scrub →
        // delete. Deleting first would orphan the closure.
        InOrder order = inOrder(jdbcTemplate, scrubAdapter);
        order.verify(jdbcTemplate).queryForList(anyString(), eq(String.class), eq(id));
        order.verify(scrubAdapter).scrubAuditColumns(any());
        order.verify(jdbcTemplate).update(anyString(), (Object) any());
    }

    @Test
    void aReRunAnswersZeroOnBothCounts() {
        UUID id = UUID.randomUUID();
        User user = pseudonymizedUser(id, "anon-" + "2".repeat(64));
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        // The mirror history is gone — the closure is the current subject
        // alone, and nothing matches it anywhere.
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(id)))
                .thenReturn(List.of());
        when(scrubAdapter.scrubAuditColumns(any())).thenReturn(0);
        when(jdbcTemplate.update(anyString(), (Object) any())).thenReturn(0);

        AuditHistoryPurgeResult result = service.purge(id, "re-run", "admin-subject");

        assertThat(result.scrubbedRows()).isZero();
        assertThat(result.usersAudRowsDeleted()).isZero();
    }
}
