package com.marketplace.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 1 (the unified plan §10, D-03) — the SPI delegation over the
 * assignment model: {@code IdentitySpi}'s grantUserRole/revokeUserRole/
 * getUserRoles ride {@link RoleAssignmentService}'s owned commands (the
 * A-07 third layer), with the grant's provenance resolving the acting
 * administrator's subject to its own row id when it is one of ours —
 * NULL for a foreign/test principal (the audit string still carries
 * the actor).
 */
class UserServiceRoleAssignmentDelegationTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleAssignmentService roleAssignmentService = mock(RoleAssignmentService.class);

    private UserService service;

    @BeforeEach
    void setUp() {
        // The delegation touches exactly two collaborators; the rest of
        // the constructor stays null (the commands under test never
        // reach them).
        service = new UserService(userRepository, null, null, null, null, null, null,
                null, null, roleAssignmentService, null);
    }

    private static com.marketplace.identity.User adminRow(String subject) {
        return com.marketplace.identity.User.create(subject, subject, "Admin", UserRole.ADMIN);
    }

    @Test
    void grantUserRole_delegatesWithTheResolvedActorId() {
        UUID targetId = UUID.randomUUID();
        com.marketplace.identity.User admin = adminRow("admin@example.com");
        when(userRepository.findBySubject("admin@example.com")).thenReturn(Optional.of(admin));

        service.grantUserRole(targetId, "PROVIDER", "admin@example.com");

        // granted_by carries the acting administrator's own row id; the
        // actor string rides the audit line verbatim.
        verify(roleAssignmentService).grant(targetId, UserRole.PROVIDER, admin.getId(),
                "admin@example.com");
    }

    @Test
    void grantUserRole_withAForeignActorRidesANullGrantedBy() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findBySubject("foreign-principal")).thenReturn(Optional.empty());

        service.grantUserRole(targetId, "PROVIDER", "foreign-principal");

        verify(roleAssignmentService).grant(targetId, UserRole.PROVIDER, null, "foreign-principal");
    }

    @Test
    void revokeUserRole_delegatesVerbatim() {
        UUID targetId = UUID.randomUUID();

        service.revokeUserRole(targetId, "PROVIDER", "admin@example.com");

        verify(roleAssignmentService).revoke(targetId, UserRole.PROVIDER, "admin@example.com");
        // The revoke resolves nothing — the command owns its own gates.
        verifyNoInteractions(userRepository);
    }

    @Test
    void getUserRoles_mapsTheActiveAssignmentsSorted() {
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        // The service's own order is newest-first (the L32 shape); the
        // SPI answers the sorted stable admin display order.
        when(roleAssignmentService.activeRoles(targetId)).thenReturn(List.of(
                new RoleAssignmentView(UUID.randomUUID(), targetId, "PROVIDER",
                        now.minusSeconds(60), null, "ADMIN", null),
                new RoleAssignmentView(UUID.randomUUID(), targetId, "CONSUMER",
                        now.minusSeconds(3600), null, "BACKFILL", null)));

        var roles = service.getUserRoles(targetId);

        assertThat(roles).containsExactly("CONSUMER", "PROVIDER");
    }

    @Test
    void getUserRoles_forAnAccountWithNoAssignmentsAnswersTheEmptySet() {
        UUID targetId = UUID.randomUUID();
        when(roleAssignmentService.activeRoles(targetId)).thenReturn(List.of());

        assertThat(service.getUserRoles(targetId)).isEmpty();
    }
}
