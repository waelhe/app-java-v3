package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.UserRoleChanged;
import com.marketplace.shared.security.AuthHelper;
import com.marketplace.shared.security.SubjectPseudonymizer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-0001 (D-03, plan §Phase 1) — the multi-role slice: grant, revoke,
 * replace, the bootstrap seed, and the standing guards. The
 * {@code UserServiceSecurityTest} shape — the method-security slice with the
 * same {@code @MockitoBean} set — so every rule here is measured positive
 * AND negative on the real proxy chain.
 *
 * <p><b>The official machinery under test:</b> the dual-store role write
 * ({@code user_roles} set + {@code users.role} primary mirror + the
 * {@code auth_authorities} projection through the framework
 * {@code UserDetailsManager.updateUser} contract) and the varargs
 * {@code roles(...)} builder carrying the FULL target set — the exact
 * authority shape every {@code hasRole} gate speaks.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { UserService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class UserServiceMultiRoleTest {

    @Autowired
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private UserDetailsManager userDetailsManager;

    @MockitoBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SubjectPseudonymizer subjectPseudonymizer;

    @MockitoBean
    private AuthoredContentPurgeService authoredContentPurgeService;

    @MockitoBean
    private AuditHistoryPurgeService auditHistoryPurgeService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private AuthActionTokenService authActionTokenService;

    @MockitoBean
    private org.springframework.beans.factory.ObjectProvider<org.springframework.security.crypto.password.PasswordEncoder> passwordEncoder;

    @MockitoBean(name = "authHelper")
    private AuthHelper authHelper;

    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String SUBJECT = "target@example.com";

    /** The login-side row BEFORE the change — the projection being replaced. */
    private static UserDetails storedLoginRow(String... roles) {
        return org.springframework.security.core.userdetails.User.withUsername(SUBJECT)
                .password("{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG")
                .roles(roles)
                .build();
    }

    private static com.marketplace.identity.User domainUser(UserRole... roles) {
        return com.marketplace.identity.User.create(SUBJECT, SUBJECT, "Target", java.util.Set.of(roles));
    }

    private void stubTarget(User domainUser, UserDetails loginRow) {
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(domainUser));
        when(userDetailsManager.loadUserByUsername(SUBJECT)).thenReturn(loginRow);
    }

    /** The ROLE_ authority names of the projected login row. */
    private static List<String> projectedAuthorities() {
        ArgumentCaptor<UserDetails> captor = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager, atLeastOnce()).updateUser(captor.capture());
        return captor.getValue().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }

    // -- The grant path: adds a role, keeps every held role --

    @Test
    @WithMockUser(roles = "ADMIN")
    void grantUserRole_addsRoleKeepsEveryHeldRole() {
        stubTarget(domainUser(UserRole.CONSUMER), storedLoginRow("CONSUMER"));

        userService.grantUserRole(TARGET_ID, "PROVIDER", "admin-1");

        // The login-side projection carries the FULL multi-role set — the
        // varargs builder shape, not a single-role replacement.
        Assertions.assertThat(projectedAuthorities())
                .containsExactlyInAnyOrder("ROLE_CONSUMER", "ROLE_PROVIDER");
        // Issued authorizations die with the change (the L23 SAS basis).
        verify(jdbcTemplate).update(UserService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL, SUBJECT);
        // The domain fact: primary CONSUMER → PROVIDER (the deterministic
        // precedence of the new set), riding the standing invalidation pair.
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        UserRoleChanged changed = events.getAllValues().stream()
                .filter(UserRoleChanged.class::isInstance)
                .map(UserRoleChanged.class::cast)
                .findFirst().orElseThrow();
        assertThat(changed.previousRole()).isEqualTo("CONSUMER");
        assertThat(changed.newRole()).isEqualTo("PROVIDER");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void grantUserRole_ofAlreadyHeldRole_isIdempotentNoOp() {
        stubTarget(domainUser(UserRole.CONSUMER), storedLoginRow("CONSUMER"));

        userService.grantUserRole(TARGET_ID, "CONSUMER", "admin-1");

        verify(userDetailsManager, never()).updateUser(any());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void grantUserRole_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> userService.grantUserRole(UUID.randomUUID(), "PROVIDER", "actor"));
        verifyNoInteractions(userRepository);
    }

    // -- The revoke path: removes one role; the guards hold --

    @Test
    @WithMockUser(roles = "ADMIN")
    void revokeUserRole_removesOnlyTheTargetRole() {
        stubTarget(domainUser(UserRole.CONSUMER, UserRole.PROVIDER), storedLoginRow("CONSUMER", "PROVIDER"));

        userService.revokeUserRole(TARGET_ID, "CONSUMER", "admin-1");

        Assertions.assertThat(projectedAuthorities())
                .containsExactlyInAnyOrder("ROLE_PROVIDER");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revokeUserRole_ofLastRemainingRole_isConflict() {
        stubTarget(domainUser(UserRole.CONSUMER), storedLoginRow("CONSUMER"));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> userService.revokeUserRole(TARGET_ID, "CONSUMER", "admin-1"));
        verify(userDetailsManager, never()).updateUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revokeUserRole_ofLastActiveAdmin_isRejected() {
        stubTarget(domainUser(UserRole.ADMIN, UserRole.PROVIDER), storedLoginRow("ADMIN", "PROVIDER"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("only-admin"));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> userService.revokeUserRole(TARGET_ID, "ADMIN", "admin-1"));
        verify(userDetailsManager, never()).updateUser(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revokeUserRole_ofAbsentRole_isIdempotentNoOp() {
        stubTarget(domainUser(UserRole.CONSUMER), storedLoginRow("CONSUMER"));

        userService.revokeUserRole(TARGET_ID, "PROVIDER", "admin-1");

        verify(userDetailsManager, never()).updateUser(any());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void revokeUserRole_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> userService.revokeUserRole(UUID.randomUUID(), "PROVIDER", "actor"));
        verifyNoInteractions(userRepository);
    }

    // -- The replace path: the historical single-role command on the new writer --

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateUserRole_replacesTheWholeSet() {
        stubTarget(domainUser(UserRole.CONSUMER, UserRole.PROVIDER), storedLoginRow("CONSUMER", "PROVIDER"));

        userService.updateUserRole(TARGET_ID, "ADMIN", "admin-1");

        Assertions.assertThat(projectedAuthorities())
                .containsExactlyInAnyOrder("ROLE_ADMIN");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateUserRole_ofUnknownRoleName_isBadRequest() {
        assertThatExceptionOfType(com.marketplace.shared.api.BadRequestException.class)
                .isThrownBy(() -> userService.grantUserRole(TARGET_ID, "SUPERUSER", "admin-1"));
    }

    // -- The bootstrap seed: the FULL authority set, not the first match --

    @Test
    void bootstrap_seedsTheFullRoleSetFromTheLiveLoginStore() {
        when(userRepository.findBySubject(SUBJECT)).thenReturn(Optional.empty());
        when(subjectPseudonymizer.deriveAll(anyString())).thenReturn(List.of());
        when(userDetailsManager.loadUserByUsername(SUBJECT)).thenReturn(storedLoginRow("ADMIN", "CONSUMER"));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.marketplace.identity.User created = userService.syncFromOidc(tokenFor(SUBJECT, List.of()));

        assertThat(created.getRoles()).containsExactlyInAnyOrder(UserRole.ADMIN, UserRole.CONSUMER);
        // The primary mirror follows the deterministic precedence.
        assertThat(created.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void bootstrap_fallsBackToTheFullClaimRoleSetWhenNoLoginAccount() {
        when(userRepository.findBySubject("new-sub")).thenReturn(Optional.empty());
        when(subjectPseudonymizer.deriveAll(anyString())).thenReturn(List.of());
        when(userDetailsManager.loadUserByUsername("new-sub"))
                .thenThrow(new UsernameNotFoundException("no login account"));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.marketplace.identity.User created =
                userService.syncFromOidc(tokenFor("new-sub", List.of("PROVIDER", "CONSUMER")));

        assertThat(created.getRoles()).containsExactlyInAnyOrder(UserRole.PROVIDER, UserRole.CONSUMER);
        assertThat(created.getRole()).isEqualTo(UserRole.PROVIDER);
    }

    private static JwtAuthenticationToken tokenFor(String subject, List<String> roles) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", subject);
        claims.put("email", SUBJECT);
        claims.put("name", "Target");
        if (!roles.isEmpty()) {
            claims.put("roles", roles);
        }
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("alg", "none"), claims);
        return new JwtAuthenticationToken(jwt);
    }
}
