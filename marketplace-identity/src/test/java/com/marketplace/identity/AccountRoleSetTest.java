package com.marketplace.identity;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserRoleChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-03 (community platform execution plan Stage 1) — the role-SET guards:
 * the set-aware authority parsing, the primary-role rank, and the
 * grant/revoke three-store discipline. The migration/backfill and the
 * end-to-end authorization ride the real PostgreSQL in
 * {@code IdentityMultiRoleIntegrationTest} (marketplace-app).
 */
@ExtendWith(MockitoExtension.class)
class AccountRoleSetTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRoleRepository accountRoleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserDetailsManager userDetailsManager;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SubjectPseudonymizer subjectPseudonymizer;

    @Mock
    private AuthoredContentPurgeService authoredContentPurgeService;

    @Mock
    private AuditHistoryPurgeService auditHistoryPurgeService;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private AuthActionTokenService authActionTokenService;

    @Mock
    private org.springframework.beans.factory.ObjectProvider<org.springframework.security.crypto.password.PasswordEncoder> passwordEncoderProvider;

    @InjectMocks
    private UserService userService;

    private static final String ACTOR = "admin-actor";

    // ---- the set-aware parsing (D-03) ------------------------------------------

    @Test
    void parseStoredRoles_collectsEveryRoleInsteadOfTheFirstMatch() {
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_CONSUMER"),
                new SimpleGrantedAuthority("ROLE_PROVIDER"));

        assertThat(UserService.parseStoredRoles(authorities))
                .containsExactlyInAnyOrder(UserRole.CONSUMER, UserRole.PROVIDER);
    }

    @Test
    void parseStoredRoles_skipsAuthoritiesOutsideTheRoleVocabulary() {
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_PROVIDER"),
                new SimpleGrantedAuthority("SCOPE_profile"),
                new SimpleGrantedAuthority("ROLE_UNKNOWN_SHAPE"));

        assertThat(UserService.parseStoredRoles(authorities))
                .containsExactly(UserRole.PROVIDER);
    }

    @Test
    void parseStoredRoles_answersTheDocumentedConsumerDefaultForAnEmptySet() {
        assertThat(UserService.parseStoredRoles(List.of()))
                .containsExactly(UserRole.CONSUMER);
    }

    @Test
    void primaryRole_ranksTheSetForTheMirror() {
        assertThat(UserService.primaryRole(Set.of(UserRole.CONSUMER, UserRole.PROVIDER)))
                .isEqualTo(UserRole.PROVIDER);
        assertThat(UserService.primaryRole(Set.of(UserRole.CONSUMER)))
                .isEqualTo(UserRole.CONSUMER);
        assertThat(UserService.primaryRole(Set.of(UserRole.PROVIDER, UserRole.ADMIN)))
                .isEqualTo(UserRole.ADMIN);
    }

    // ---- the automatic registration grant ---------------------------------------

    @Test
    void register_grantsTheConsumerRoleAutomatically_withProvenance() {
        User user = User.create("new-subject", "n@b.com", "New", UserRole.CONSUMER);
        when(userRepository.existsBySubject("new-subject")).thenReturn(false);
        when(userDetailsManager.userExists("new-subject")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(passwordEncoderProvider.getObject()).thenReturn(
                org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder());

        userService.register("n@b.com", "long-enough-pass", "New");

        ArgumentCaptor<AccountRole> grants = ArgumentCaptor.forClass(AccountRole.class);
        verify(accountRoleRepository).save(grants.capture());
        assertThat(grants.getValue().getRole()).isEqualTo(UserRole.CONSUMER);
        assertThat(grants.getValue().getSource()).isEqualTo(RoleGrantSource.REGISTRATION);
        assertThat(grants.getValue().getUserId()).isEqualTo(user.getId());
    }

    // ---- the administrative grant -------------------------------------------------

    @Test
    void grantRole_addsTheRow_elevatesTheMirror_syncsTheProjection_andKillsAuthorizations() {
        User user = User.create("grantee-sub", "g@b.com", "Grantee", UserRole.CONSUMER);
        UUID userId = user.getId();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(accountRoleRepository.existsByUserIdAndRole(userId, UserRole.PROVIDER)).thenReturn(false);
        // roleSetOf is read four times: before the grant, after the grant,
        // inside the projection sync, and in the audit line — the save is
        // mocked, so the sequence models the row landing between reads.
        when(accountRoleRepository.findByUserId(userId)).thenReturn(
                List.of(consumerRow(userId)),
                List.of(consumerRow(userId), AccountRole.grant(userId, UserRole.PROVIDER, ACTOR, RoleGrantSource.ADMIN_GRANT)),
                List.of(consumerRow(userId), AccountRole.grant(userId, UserRole.PROVIDER, ACTOR, RoleGrantSource.ADMIN_GRANT)),
                List.of(consumerRow(userId), AccountRole.grant(userId, UserRole.PROVIDER, ACTOR, RoleGrantSource.ADMIN_GRANT)));
        when(userDetailsManager.loadUserByUsername("grantee-sub")).thenReturn(
                org.springframework.security.core.userdetails.User
                        .withUsername("grantee-sub")
                        .password("{noop}pw")
                        .roles("CONSUMER")
                        .build());

        userService.grantRole(userId, "PROVIDER", ACTOR);

        ArgumentCaptor<AccountRole> grants = ArgumentCaptor.forClass(AccountRole.class);
        verify(accountRoleRepository).save(grants.capture());
        assertThat(grants.getValue().getRole()).isEqualTo(UserRole.PROVIDER);
        assertThat(grants.getValue().getSource()).isEqualTo(RoleGrantSource.ADMIN_GRANT);
        assertThat(grants.getValue().getGrantedBy()).isEqualTo(ACTOR);
        // The mirror followed the elevated top role.
        assertThat(user.getRole()).isEqualTo(UserRole.PROVIDER);
        // The projection carries BOTH roles — the set, not the single role.
        ArgumentCaptor<UserDetails> projection = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(projection.capture());
        assertThat(projection.getValue().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CONSUMER", "ROLE_PROVIDER");
        verify(jdbcTemplate).update(UserService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL, "grantee-sub");
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
        verify(eventPublisher).publishEvent(any(UserRoleChanged.class));
    }

    @Test
    void grantRole_answersTheIdempotentNoOpForAnAlreadyHeldRole() {
        User user = User.create("held-sub", "h@b.com", "Held", UserRole.CONSUMER);
        UUID userId = user.getId();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(accountRoleRepository.existsByUserIdAndRole(userId, UserRole.CONSUMER)).thenReturn(true);

        userService.grantRole(userId, "CONSUMER", ACTOR);

        verify(accountRoleRepository, never()).save(any());
        verify(userDetailsManager, never()).updateUser(any());
        verify(jdbcTemplate, never()).update(anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void grantRole_rejectsARoleOutsideTheVocabularyWithTheClean400() {
        // parseRole pins the vocabulary BEFORE any store is touched — no
        // stub needed (and strict stubs would flag one).
        assertThatThrownBy(() -> userService.grantRole(UUID.randomUUID(), "SUPERUSER", ACTOR))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
    }

    // ---- the administrative revoke --------------------------------------------------

    @Test
    void revokeRole_removesTheRow_andDropsTheProjectionToTheRemainingSet() {
        User user = User.create("dual-sub", "d@b.com", "Dual", UserRole.PROVIDER);
        UUID userId = user.getId();
        AccountRole providerRow = AccountRole.grant(userId, UserRole.PROVIDER, ACTOR, RoleGrantSource.ADMIN_GRANT);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(accountRoleRepository.findByUserIdAndRole(userId, UserRole.PROVIDER))
                .thenReturn(Optional.of(providerRow));
        when(accountRoleRepository.findByUserId(userId)).thenReturn(
                List.of(consumerRow(userId), providerRow),
                List.of(consumerRow(userId)),
                List.of(consumerRow(userId)));
        when(userDetailsManager.loadUserByUsername("dual-sub")).thenReturn(
                org.springframework.security.core.userdetails.User
                        .withUsername("dual-sub")
                        .password("{noop}pw")
                        .roles("CONSUMER", "PROVIDER")
                        .build());

        userService.revokeRole(userId, "PROVIDER", ACTOR);

        verify(accountRoleRepository).delete(providerRow);
        assertThat(user.getRole()).isEqualTo(UserRole.CONSUMER);
        ArgumentCaptor<UserDetails> projection = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(projection.capture());
        assertThat(projection.getValue().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CONSUMER");
        verify(jdbcTemplate).update(UserService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL, "dual-sub");
    }

    @Test
    void revokeRole_answers404ForARoleTheAccountDoesNotHold() {
        User user = User.create("solo-sub", "s@b.com", "Solo", UserRole.CONSUMER);
        UUID userId = user.getId();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(accountRoleRepository.findByUserIdAndRole(userId, UserRole.PROVIDER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.revokeRole(userId, "PROVIDER", ACTOR))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void revokeRole_refusesToEmptyTheSet() {
        User user = User.create("only-sub", "o@b.com", "Only", UserRole.CONSUMER);
        UUID userId = user.getId();
        AccountRole consumerRow = consumerRow(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(accountRoleRepository.findByUserIdAndRole(userId, UserRole.CONSUMER))
                .thenReturn(Optional.of(consumerRow));
        when(accountRoleRepository.findByUserId(userId)).thenReturn(List.of(consumerRow));

        assertThatThrownBy(() -> userService.revokeRole(userId, "CONSUMER", ACTOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("last role");
        verify(accountRoleRepository, never()).delete(any());
    }

    @Test
    void revokeRole_enforcesTheLastActiveAdminInvariant() {
        User user = User.create("last-admin-sub", "la@b.com", "Last Admin", UserRole.ADMIN);
        UUID userId = user.getId();
        AccountRole adminRow = AccountRole.grant(userId, UserRole.ADMIN, ACTOR, RoleGrantSource.ADMIN_GRANT);
        AccountRole consumerRow = consumerRow(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(accountRoleRepository.findByUserIdAndRole(userId, UserRole.ADMIN))
                .thenReturn(Optional.of(adminRow));
        when(accountRoleRepository.findByUserId(userId)).thenReturn(List.of(adminRow, consumerRow));
        when(jdbcTemplate.queryForList(eq(UserService.LOCK_ACTIVE_ADMINS), eq(String.class)))
                .thenReturn(List.of("last-admin-sub"));

        assertThatThrownBy(() -> userService.revokeRole(userId, "ADMIN", ACTOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("last active ADMIN");
        verify(accountRoleRepository, never()).delete(any());
    }

    // ---- helpers ---------------------------------------------------------------------

    private static AccountRole consumerRow(UUID userId) {
        return AccountRole.grant(userId, UserRole.CONSUMER, "SYSTEM", RoleGrantSource.REGISTRATION);
    }
}
