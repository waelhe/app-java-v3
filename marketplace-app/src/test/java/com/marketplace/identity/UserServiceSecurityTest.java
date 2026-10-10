package com.marketplace.identity;

import com.marketplace.shared.security.AuthHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-07 (official-compliance plan §6 wave A — A.5, «اختبارات تفويض لكل مسار
 * حساس»): the method-security slice of the identity admin surface — the
 * service-level third layer of the documented three-layer admin pattern,
 * measured positive AND negative per rule (the standing convention every
 * {@code *ServiceSecurityTest} follows; same shape as
 * {@code LedgerServiceSecurityTest}).
 *
 * <p><b>The dual-contract rule on {@code pseudonymizeAccount}</b> is the
 * interesting one: the method has TWO documented entry surfaces with
 * different authorization contracts (the N2 precedent —
 * {@code PaymentIntentSettlementService}): the admin erasure through
 * {@code IdentitySpi} and the self-service deletion journey
 * ({@code AccountSelfDeletionService} — token subject + password
 * re-verification). The rule is the honest OR: an ADMIN may target any
 * account; anyone else must BE the account. The self leg is measured here
 * through the same {@code @authHelper.isCurrentUser} predicate the
 * production SpEL evaluates.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { UserService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class UserServiceSecurityTest {

    @Autowired
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private UserDetailsManager userDetailsManager;

    @MockitoBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @MockitoBean
    private com.marketplace.shared.security.SubjectPseudonymizer subjectPseudonymizer;

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

    private static UserDetails storedLoginRow() {
        return User.withUsername("target@example.com")
                .password("{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG")
                .roles("CONSUMER")
                .build();
    }

    /** The identity domain row (fully qualified — the Spring Security {@code User} is imported for the login-row fixture above). */
    private static com.marketplace.identity.User domainUser(String subject) {
        return com.marketplace.identity.User.create(subject, subject, "Target", UserRole.CONSUMER);
    }

    // -- The admin-only rules: negative first (the measured gap before A.5) --

    @Test
    @WithMockUser(roles = "CONSUMER")
    void updateUserRole_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> userService.updateUserRole(UUID.randomUUID(), "PROVIDER", "actor"));
        verifyNoInteractions(userRepository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void updateUserStatus_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> userService.updateUserStatus(UUID.randomUUID(), "DISABLED", "reason", "actor"));
        verifyNoInteractions(userRepository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void purgeAuthoredContent_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> userService.purgeAuthoredContent(UUID.randomUUID(), "reason", "actor"));
        verifyNoInteractions(authoredContentPurgeService);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void purgeAuditHistory_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> userService.purgeAuditHistory(UUID.randomUUID(), "reason", "actor"));
        verifyNoInteractions(auditHistoryPurgeService);
    }

    // -- The dual-contract rule on pseudonymizeAccount --

    @Test
    @WithMockUser(roles = "CONSUMER")
    void pseudonymizeAccount_whenForeignAccount_thenAccessDenied() {
        UUID foreign = UUID.randomUUID();
        when(authHelper.isCurrentUser(eq(foreign), any())).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> userService.pseudonymizeAccount(foreign, "reason", "actor"));
        verifyNoInteractions(userRepository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void pseudonymizeAccount_whenOwnAccount_thenProceedsToTheDomain() {
        UUID own = UUID.randomUUID();
        when(authHelper.isCurrentUser(eq(own), any())).thenReturn(true);
        when(userRepository.findById(own)).thenReturn(Optional.of(domainUser("self@example.com")));
        when(userDetailsManager.loadUserByUsername("self@example.com")).thenReturn(storedLoginRow());
        when(subjectPseudonymizer.derive("self@example.com")).thenReturn("anon-derived");

        assertThatCode(() -> userService.pseudonymizeAccount(own, "self-deletion", "self"))
                .as("the self-service deletion journey must reach the domain (the A-05 contract)")
                .doesNotThrowAnyException();
        verify(userRepository).findById(own);
        verify(userDetailsManager).deleteUser("self@example.com");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void pseudonymizeAccount_whenAdmin_thenShortCircuitsWithoutOwnershipLookup() {
        UUID anyTarget = UUID.randomUUID();
        when(userRepository.findById(anyTarget)).thenReturn(Optional.of(domainUser("target@example.com")));
        when(userDetailsManager.loadUserByUsername("target@example.com")).thenReturn(storedLoginRow());
        when(subjectPseudonymizer.derive("target@example.com")).thenReturn("anon-derived");

        assertThatCode(() -> userService.pseudonymizeAccount(anyTarget, "gdpr", "admin"))
                .doesNotThrowAnyException();

        // SpEL's `or` short-circuits: hasRole('ADMIN') decided, so the
        // ownership predicate is never consulted on the admin path.
        verify(authHelper, never()).isCurrentUser(any(UUID.class), any());
    }

    // -- The admin-only rules: positives (the domain executes) --

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateUserRole_whenAdmin_thenReachesTheDomain() {
        UUID target = UUID.randomUUID();
        when(userRepository.findById(target)).thenReturn(Optional.of(domainUser("target@example.com")));
        when(userDetailsManager.loadUserByUsername(anyString())).thenReturn(storedLoginRow());

        assertThatCode(() -> userService.updateUserRole(target, "PROVIDER", "admin")).doesNotThrowAnyException();

        verify(userRepository).findById(target);
        verify(userDetailsManager).updateUser(any(UserDetails.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateUserStatus_whenAdmin_thenReachesTheDomain() {
        UUID target = UUID.randomUUID();
        when(userRepository.findById(target)).thenReturn(Optional.of(domainUser("target@example.com")));
        when(userDetailsManager.loadUserByUsername(anyString())).thenReturn(storedLoginRow());

        assertThatCode(() -> userService.updateUserStatus(target, "DISABLED", "reason", "admin"))
                .doesNotThrowAnyException();

        verify(userDetailsManager).updateUser(any(UserDetails.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void purgeAuthoredContent_whenAdmin_thenDelegates() {
        UUID target = UUID.randomUUID();
        when(authoredContentPurgeService.purge(target, "reason", "admin")).thenReturn(7);

        assertThatCode(() -> userService.purgeAuthoredContent(target, "reason", "admin"))
                .doesNotThrowAnyException();

        verify(authoredContentPurgeService).purge(target, "reason", "admin");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void purgeAuditHistory_whenAdmin_thenDelegates() {
        UUID target = UUID.randomUUID();
        when(auditHistoryPurgeService.purge(target, "reason", "admin"))
                .thenReturn(new com.marketplace.identity.spi.AuditHistoryPurgeResult(3, 2));

        assertThatCode(() -> userService.purgeAuditHistory(target, "reason", "admin"))
                .doesNotThrowAnyException();

        verify(auditHistoryPurgeService).purge(target, "reason", "admin");
    }
}
