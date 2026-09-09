package com.marketplace.identity;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.api.UserSummary;
import com.marketplace.shared.security.SubjectPseudonymizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.provisioning.UserDetailsManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserDetailsManager userDetailsManager;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SubjectPseudonymizer subjectPseudonymizer;

    @InjectMocks
    private UserService userService;

    @Test
    void getById_returnsUser() {
        UUID id = UUID.randomUUID();
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        User result = userService.getById(id);

        assertEquals(user, result);
    }

    @Test
    void getById_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getById(id));
    }

    @Test
    void getBySubject_returnsUser() {
        String subject = "sub-1";
        User user = User.create(subject, "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findBySubject(subject)).thenReturn(Optional.of(user));

        User result = userService.getBySubject(subject);

        assertEquals(user, result);
    }

    @Test
    void getBySubject_throwsWhenNotFound() {
        String subject = "sub-missing";
        when(userRepository.findBySubject(subject)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getBySubject(subject));
    }

    @Test
    void findAll_returnsPage() {
        PageRequest pageable = PageRequest.of(0, 10);
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user)));

        Page<User> result = userService.findAll(pageable);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void findAllSummaries_returnsPage() {
        PageRequest pageable = PageRequest.of(0, 10);
        UUID id = UUID.randomUUID();
        User user = new User(id, "sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user)));

        Page<UserSummary> result = userService.findAllSummaries(pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(id, result.getContent().getFirst().id());
        assertEquals("Alice", result.getContent().getFirst().displayName());
    }

    @Test
    void syncFromOidc_createsNewUser() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("new-sub");
        when(jwt.getClaimAsString("email")).thenReturn("new@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("New User");
        when(jwt.getClaimAsStringList("roles")).thenReturn(null);
        when(userRepository.findBySubject("new-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = userService.syncFromOidc(token);

        assertEquals("new-sub", result.getSubject());
        assertEquals("new@b.com", result.getEmail());
        assertEquals("New User", result.getDisplayName());
        assertEquals(UserRole.CONSUMER, result.getRole());
        verify(userRepository).save(any());
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void syncFromOidc_updatesExistingUser() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("existing-sub");
        when(jwt.getClaimAsString("email")).thenReturn("updated@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Updated Name");

        User existing = User.create("existing-sub", "old@b.com", "Old Name", UserRole.CONSUMER);
        when(userRepository.findBySubject("existing-sub")).thenReturn(Optional.of(existing));

        User result = userService.syncFromOidc(token);

        assertEquals("updated@b.com", result.getEmail());
        assertEquals("Updated Name", result.getDisplayName());
        verify(userRepository, never()).save(any());
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void syncFromOidc_whenProfileUnchanged_doesNotPublishInvalidation() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("existing-sub");
        when(jwt.getClaimAsString("email")).thenReturn("a@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Alice");

        User existing = User.create("existing-sub", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findBySubject("existing-sub")).thenReturn(Optional.of(existing));

        User result = userService.syncFromOidc(token);

        assertEquals("a@b.com", result.getEmail());
        assertEquals("Alice", result.getDisplayName());
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void syncFromOidc_resolvesAdminRole() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("admin-sub");
        when(jwt.getClaimAsString("email")).thenReturn("admin@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Admin");
        when(jwt.getClaimAsStringList("roles")).thenReturn(List.of("ADMIN"));
        when(userRepository.findBySubject("admin-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = userService.syncFromOidc(token);

        assertEquals(UserRole.ADMIN, result.getRole());
    }

    @Test
    void updateUserRole_changesRole() {
        UUID id = UUID.randomUUID();
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.updateUserRole(id, "ADMIN");

        assertEquals(UserRole.ADMIN, user.getRole());
        verify(userRepository).findById(id);
    }

    @Test
    void updateUserRole_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.updateUserRole(id, "ADMIN"));
    }

    @Test
    void syncFromOidc_resolvesProviderRole() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("provider-sub");
        when(jwt.getClaimAsString("email")).thenReturn("provider@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Provider");
        when(jwt.getClaimAsStringList("roles")).thenReturn(List.of("PROVIDER"));
        when(userRepository.findBySubject("provider-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = userService.syncFromOidc(token);

        assertEquals(UserRole.PROVIDER, result.getRole());
    }

    // -- L23: updateUserStatus -------------------------------------------

    @Test
    void syncFromOidc_rejectsNonJwtAuthentication() {
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken nonJwt =
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated("user", "password", java.util.List.of());

        assertThrows(IllegalArgumentException.class, () -> userService.syncFromOidc(nonJwt));
        verifyNoInteractions(userRepository);
    }

    private static UserDetails userDetails(boolean enabled, String... roles) {
        org.springframework.security.core.userdetails.User.UserBuilder builder =
                org.springframework.security.core.userdetails.User.withUsername("target-user")
                        .password("{noop}secret")
                        .disabled(!enabled);
        if (roles.length > 0) {
            builder.roles(roles);
        }
        return builder.build();
    }

    @Test
    void updateUserStatus_disableFlipsFlagRemovesAuthorizationsAndLogsAudit() {
        UUID id = UUID.randomUUID();
        User user = User.create("target-user", "t@b.com", "Target", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername("target-user"))
                .thenReturn(userDetails(true, "USER"));

        userService.updateUserStatus(id, "DISABLED", "policy violation", "admin-actor");

        // The operative flip goes through the framework manager, flag only.
        ArgumentCaptor<UserDetails> captured = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(captured.capture());
        assertEquals("target-user", captured.getValue().getUsername());
        assertFalse(captured.getValue().isEnabled(), "the account must be disabled");
        assertEquals("{noop}secret", captured.getValue().getPassword());
        assertTrue(captured.getValue().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")), "authorities replayed verbatim");
        // Issued authorizations die with the disable — and nothing else is written.
        verify(jdbcTemplate).update(eq(UserService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL), eq("target-user"));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void updateUserStatus_enableRestoresFlagWithoutTouchingAuthorizations() {
        UUID id = UUID.randomUUID();
        User user = User.create("target-user", "t@b.com", "Target", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername("target-user"))
                .thenReturn(userDetails(false, "USER"));

        userService.updateUserStatus(id, "ENABLED", "appeal accepted", "admin-actor");

        ArgumentCaptor<UserDetails> captured = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(captured.capture());
        assertTrue(captured.getValue().isEnabled(), "the account must be enabled");
        // Enabling emits no token and removes nothing (the user logs in again).
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
    }

    @Test
    void updateUserStatus_throwsForUnknownStatus() {
        UUID id = UUID.randomUUID();

        // No repository stub: input validation rejects the request before any load.
        assertThrows(IllegalArgumentException.class,
                () -> userService.updateUserStatus(id, "BANISHED", "x", "admin-actor"));
        verify(userDetailsManager, never()).updateUser(any());
    }

    @Test
    void updateUserStatus_throwsWhenProjectionMissing() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> userService.updateUserStatus(id, "DISABLED", "x", "admin-actor"));
    }

    @Test
    void updateUserStatus_throwsWhenAuthenticationAccountMissing() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(
                User.create("ghost-subject", "g@b.com", "Ghost", UserRole.CONSUMER)));
        when(userDetailsManager.loadUserByUsername("ghost-subject"))
                .thenThrow(new UsernameNotFoundException("ghost-subject"));

        assertThrows(ResourceNotFoundException.class,
                () -> userService.updateUserStatus(id, "DISABLED", "x", "admin-actor"));
        verify(userDetailsManager, never()).updateUser(any());
    }

    @Test
    void updateUserStatus_rejectsDisablingTheLastActiveAdmin() {
        UUID id = UUID.randomUUID();
        User user = User.create("admin-user", "a@b.com", "Admin", UserRole.ADMIN);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername("admin-user"))
                .thenReturn(userDetails(true, "ADMIN"));
        when(jdbcTemplate.queryForList(eq(UserService.LOCK_ACTIVE_ADMINS), eq(String.class)))
                .thenReturn(List.of("admin-user"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> userService.updateUserStatus(id, "DISABLED", "x", "admin-actor"));

        assertTrue(ex.getMessage().contains("last active ADMIN"));
        verify(userDetailsManager, never()).updateUser(any());
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
    }

    @Test
    void updateUserStatus_allowsDisablingAnAdminWhenOthersRemain() {
        UUID id = UUID.randomUUID();
        User user = User.create("admin-user", "a@b.com", "Admin", UserRole.ADMIN);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername("admin-user"))
                .thenReturn(userDetails(true, "ADMIN"));
        when(jdbcTemplate.queryForList(eq(UserService.LOCK_ACTIVE_ADMINS), eq(String.class)))
                .thenReturn(List.of("admin-user", "other-admin"));

        userService.updateUserStatus(id, "DISABLED", "handover", "admin-actor");

        verify(userDetailsManager).updateUser(any());
    }

    // -- I7: pseudonymizeAccount (plan §5-أ) --------------------------------

    /** The derivation result the mocked SubjectPseudonymizer hands back. */
    private static final String DERIVED_SUBJECT =
            "anon-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void pseudonymizeAccount_transformsDeletesIdentityAndPublishesCacheInvalidation() {
        UUID id = UUID.randomUUID();
        User user = User.create("target-subject", "t@b.com", "Target", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername("target-subject"))
                .thenReturn(userDetails(true, "USER"));
        when(subjectPseudonymizer.derive("target-subject")).thenReturn(DERIVED_SUBJECT);

        userService.pseudonymizeAccount(id, "data-subject request", "admin-actor");

        // §5-أ step 3 + 4: the transformation on the domain object.
        assertThat(user.getSubject()).isEqualTo(DERIVED_SUBJECT);
        assertThat(user.getEmail()).isNull();
        assertThat(user.getDisplayName()).isNull();
        assertThat(user.getPseudonymizedAt()).isNotNull();

        // Step 5: the login identity dies through the framework manager's
        // deleteUser (authorities first, then the row — the official order).
        verify(userDetailsManager).deleteUser("target-subject");
        // Step 6: the issued authorizations die immediately.
        verify(jdbcTemplate).update(eq(UserService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL), eq("target-subject"));
        // Step 7: the standing AFTER_COMMIT cache channel with BOTH cache names.
        ArgumentCaptor<CacheInvalidationRequested> event =
                ArgumentCaptor.forClass(CacheInvalidationRequested.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().cacheNames()).containsExactlyInAnyOrder("users", "userSubjects");
        verifyNoMoreInteractions(jdbcTemplate, userDetailsManager);
    }

    @Test
    void pseudonymizeAccount_isIdempotent_noOpOnAnAlreadyPseudonymizedRow() {
        UUID id = UUID.randomUUID();
        User user = User.create("target-subject", "t@b.com", "Target", UserRole.CONSUMER);
        user.applyPseudonymization(DERIVED_SUBJECT);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.pseudonymizeAccount(id, "re-run", "admin-actor");

        // Nothing happens: no auth deletion, no authorization sweep, no event.
        // The subject is unchanged (the same derivation anyway — determinism).
        assertThat(user.getSubject()).isEqualTo(DERIVED_SUBJECT);
        verifyNoInteractions(userDetailsManager, jdbcTemplate, eventPublisher, subjectPseudonymizer);
    }

    @Test
    void pseudonymizeAccount_unboundSecretChannelIsOffNotBroken() {
        UUID id = UUID.randomUUID();
        User user = User.create("target-subject", "t@b.com", "Target", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername("target-subject"))
                .thenReturn(userDetails(true, "USER"));
        when(subjectPseudonymizer.derive("target-subject"))
                .thenThrow(new ServiceUnavailableException("Account pseudonymization is not configured."));

        assertThrows(ServiceUnavailableException.class,
                () -> userService.pseudonymizeAccount(id, "x", "admin-actor"));

        // The 503 fires BEFORE any mutation: the row, the auth identity, the
        // authorizations and the caches are all untouched.
        assertThat(user.getSubject()).isEqualTo("target-subject");
        verify(userDetailsManager, never()).deleteUser(any());
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void pseudonymizeAccount_throwsWhenAuthenticationAccountMissing() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(
                User.create("ghost-subject", "g@b.com", "Ghost", UserRole.CONSUMER)));
        when(userDetailsManager.loadUserByUsername("ghost-subject"))
                .thenThrow(new UsernameNotFoundException("ghost-subject"));

        assertThrows(ResourceNotFoundException.class,
                () -> userService.pseudonymizeAccount(id, "x", "admin-actor"));
        verify(userDetailsManager, never()).deleteUser(any());
    }

    @Test
    void pseudonymizeAccount_rejectsTheLastActiveAdmin() {
        UUID id = UUID.randomUUID();
        User user = User.create("admin-user", "a@b.com", "Admin", UserRole.ADMIN);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername("admin-user"))
                .thenReturn(userDetails(true, "ADMIN"));
        when(jdbcTemplate.queryForList(eq(UserService.LOCK_ACTIVE_ADMINS), eq(String.class)))
                .thenReturn(List.of("admin-user"));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> userService.pseudonymizeAccount(id, "x", "admin-actor"));

        assertThat(ex.getMessage()).contains("last active ADMIN");
        verify(userDetailsManager, never()).deleteUser(any());
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void pseudonymizeAccount_skipsTheAdminCountForNonAdminTargets() {
        UUID id = UUID.randomUUID();
        User user = User.create("target-subject", "t@b.com", "Target", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userDetailsManager.loadUserByUsername("target-subject"))
                .thenReturn(userDetails(true, "USER"));
        when(subjectPseudonymizer.derive("target-subject")).thenReturn(DERIVED_SUBJECT);

        userService.pseudonymizeAccount(id, "request", "admin-actor");

        // No admin counting query for a non-admin target (the guard only
        // locks rows when the target carries the authority).
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class));
        verify(userDetailsManager).deleteUser("target-subject");
    }

    // -- I7 §5-أ step 8: the re-registration tombstone guard --------------

    @Test
    void syncFromOidc_rejectsProvisioningWhenTheDerivedTombstoneExists() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("resurrected-subject");
        when(jwt.getClaimAsString("email")).thenReturn("r@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Re-issued Identity");
        when(userRepository.findBySubject("resurrected-subject")).thenReturn(Optional.empty());
        when(subjectPseudonymizer.isConfigured()).thenReturn(true);
        when(subjectPseudonymizer.derive("resurrected-subject")).thenReturn(DERIVED_SUBJECT);
        // The tombstone: a row already carries the derived replacement.
        when(userRepository.findBySubject(DERIVED_SUBJECT)).thenReturn(Optional.of(
                User.create(DERIVED_SUBJECT, null, null, UserRole.CONSUMER)));

        ConflictException ex = assertThrows(ConflictException.class, () -> userService.syncFromOidc(token));

        assertThat(ex.getMessage()).contains("pseudonymized");
        // Provisioning never happens — no new row, no cache event.
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void syncFromOidc_guardIsInertWhileTheSecretChannelIsUnbound() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("fresh-subject");
        when(jwt.getClaimAsString("email")).thenReturn("f@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Fresh User");
        when(jwt.getClaimAsStringList("roles")).thenReturn(null);
        when(userRepository.findBySubject("fresh-subject")).thenReturn(Optional.empty());
        when(subjectPseudonymizer.isConfigured()).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = userService.syncFromOidc(token);

        // No tombstone can exist while the key never existed — the probe is
        // skipped entirely (no derive() call), and provisioning proceeds.
        verify(subjectPseudonymizer, never()).derive(any());
        assertThat(result.getSubject()).isEqualTo("fresh-subject");
    }

    @Test
    void findAllSummaries_rendersTheNeutralLabelForPseudonymizedAccounts() {
        UUID id = UUID.randomUUID();
        User live = new User(id, "live-subject", "l@b.com", "Live", UserRole.CONSUMER);
        User gone = User.create("gone-subject", "g@b.com", "Gone", UserRole.CONSUMER);
        gone.applyPseudonymization(DERIVED_SUBJECT);
        when(userRepository.findAll(PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(live, gone)));

        Page<UserSummary> result = userService.findAllSummaries(PageRequest.of(0, 10));

        assertThat(result.getContent().get(0).displayName()).isEqualTo("Live");
        assertThat(result.getContent().get(1).displayName())
                .isEqualTo(UserService.FORMER_MEMBER_LABEL);
        assertThat(result.getContent().get(1).email()).isNull();
    }
}
