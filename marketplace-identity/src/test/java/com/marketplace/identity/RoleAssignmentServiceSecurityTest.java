package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 1 (the unified plan §10, D-03) — the method-security slice of
 * the multi-role surface: the ADMIN service gate of the A-07
 * three-layer pattern, measured positive AND negative per command (the
 * standing {@code *ServiceSecurityTest} convention,
 * {@code UserServiceSecurityTest}'s exact shape — the commands are bean
 * methods callable OFF the HTTP path, and «unannotated methods are not
 * secured» is the Spring Security Reference's own words, so the gate
 * lives ON the service).
 *
 * <p>The negative legs are the Phase-1 acceptance's own negative
 * security row: granting or revoking a role WITHOUT the ADMIN
 * authority is refused before the domain executes — no repository
 * interaction, no event, no kill SQL.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { RoleAssignmentService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class RoleAssignmentServiceSecurityTest {

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @MockitoBean
    private RoleAssignmentRepository roleAssignmentRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    // -- The admin-only rules: negative first (the Phase-1 negative row) --

    @Test
    @WithMockUser(roles = "CONSUMER")
    void grant_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> roleAssignmentService.grant(
                        UUID.randomUUID(), UserRole.PROVIDER, UUID.randomUUID(), "member"));
        verifyNoInteractions(userRepository, roleAssignmentRepository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void revoke_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> roleAssignmentService.revoke(
                        UUID.randomUUID(), UserRole.PROVIDER, "member"));
        verifyNoInteractions(userRepository, roleAssignmentRepository, jdbcTemplate, eventPublisher);
    }

    // -- The admin-only rules: positives (the domain executes) --

    @Test
    @WithMockUser(roles = "ADMIN")
    void grant_whenAdmin_thenReachesTheDomain() {
        UUID userId = UUID.randomUUID();
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                com.marketplace.identity.User.create(
                        "member@example.com", "member@example.com", "Member", UserRole.CONSUMER)));
        when(roleAssignmentRepository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.empty());
        when(roleAssignmentRepository.save(any(RoleAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> roleAssignmentService.grant(
                        userId, UserRole.PROVIDER, UUID.randomUUID(), "admin"))
                .doesNotThrowAnyException();

        verify(userRepository).findById(userId);
        verify(roleAssignmentRepository).save(any(RoleAssignment.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revoke_whenAdmin_thenReachesTheDomain() {
        UUID userId = UUID.randomUUID();
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                com.marketplace.identity.User.create(
                        "member@example.com", "member@example.com", "Member", UserRole.CONSUMER)));
        when(roleAssignmentRepository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.of(RoleAssignment.grant(
                        UUID.randomUUID(), userId, UserRole.PROVIDER,
                        Instant.EPOCH, null, RoleAssignmentService.SOURCE_ADMIN)));

        assertThatCode(() -> roleAssignmentService.revoke(userId, UserRole.PROVIDER, "admin"))
                .doesNotThrowAnyException();

        verify(userRepository).findById(userId);
        // The L23 refresh-resurrection kill — the revoke's own carrier.
        verify(jdbcTemplate).update(
                RoleAssignmentService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL, "member@example.com");
    }
}
