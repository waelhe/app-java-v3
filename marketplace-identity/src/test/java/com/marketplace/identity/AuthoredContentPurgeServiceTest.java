package com.marketplace.identity;

import java.util.List;
import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I7 Phase 3 (gate b-3 — the free-text purge): the orchestrator's guards.
 * The full end-state (base + Envers mirrors, counterparty isolation) is
 * the integration guard's job on real PostgreSQL; here the contract
 * under test is the orchestration itself: the pseudonymization gate, the
 * fan-out to every port, and the exact-count aggregation.
 */
class AuthoredContentPurgeServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthoredContentPurgePort portA = mock(AuthoredContentPurgePort.class);
    private final AuthoredContentPurgePort portB = mock(AuthoredContentPurgePort.class);
    private final AuthoredContentPurgeService service =
            new AuthoredContentPurgeService(userRepository, List.of(portA, portB));

    private static User pseudonymizedUser(UUID id) {
        User user = new User(id, "anon-" + "0".repeat(64), null, null, UserRole.CONSUMER);
        user.applyPseudonymization("anon-" + "1".repeat(64));
        return user;
    }

    @Test
    void unknownUserAnswersNotFoundBeforeAnyPortRuns() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(java.util.Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.purge(id, "legal demand", "admin-subject"));

        verify(portA, never()).purgeAuthoredTexts(any());
        verify(portB, never()).purgeAuthoredTexts(any());
    }

    @Test
    void liveAccountIsRejectedWithConflictBeforeAnyStatementRuns() {
        UUID id = UUID.randomUUID();
        User live = new User(id, "live-subject", "live@example.com", "Live", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(java.util.Optional.of(live));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.purge(id, "legal demand", "admin-subject"));

        // The guard's contract: the purge completes an erasure flow — the
        // target must already be pseudonymized.
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("pseudonymized"));
        verify(portA, never()).purgeAuthoredTexts(any());
        verify(portB, never()).purgeAuthoredTexts(any());
    }

    @Test
    void everyPortRunsOnceAndCountsSumExactly() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(java.util.Optional.of(pseudonymizedUser(id)));
        when(portA.purgeAuthoredTexts(id)).thenReturn(3);
        when(portB.purgeAuthoredTexts(id)).thenReturn(4);

        int total = service.purge(id, "legal demand", "admin-subject");

        assertEquals(7, total);
        verify(portA).purgeAuthoredTexts(id);
        verify(portB).purgeAuthoredTexts(id);
    }

    @Test
    void aReRunAggregatesThePortsOwnZeroes() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(java.util.Optional.of(pseudonymizedUser(id)));
        when(portA.purgeAuthoredTexts(id)).thenReturn(0);
        when(portB.purgeAuthoredTexts(id)).thenReturn(0);

        assertEquals(0, service.purge(id, "re-run", "admin-subject"));
    }
}
