package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the Saleor 458-root campaign, PR #696): the token-based
 * self-service legs' owning guards — the deletion journey's issue/redeem
 * pair over the V251 purpose, and the password change's verify-rotate-kill
 * contract.
 */
@ExtendWith(MockitoExtension.class)
class AccountSelfServiceTokenServiceTest {

    private static final String SUBJECT = "member@example.com";

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetailsManager userDetailsManager;
    @Mock
    private AuthActionTokenService authActionTokenService;
    @Mock
    private UserService userService;
    @Mock
    private ObjectProvider<PasswordEncoder> encoder;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AccountSelfServiceTokenService service;

    private Authentication caller;
    private User userRow;

    @BeforeEach
    void setUp() {
        service = new AccountSelfServiceTokenService(userRepository, userDetailsManager,
                authActionTokenService, userService, encoder, jdbcTemplate, eventPublisher);
        caller = new UsernamePasswordAuthenticationToken(SUBJECT, null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
        userRow = User.create(SUBJECT, SUBJECT, "Member", UserRole.CONSUMER);
    }

    @Test
    void requestDeletionIssuesTheRightAndPublishesTheMailEvent() {
        when(userRepository.findBySubject(SUBJECT)).thenReturn(Optional.of(userRow));
        when(authActionTokenService.issue(SUBJECT, AuthActionTokenPurpose.ACCOUNT_DELETION,
                AccountSelfServiceTokenService.DELETION_TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "raw-token", Instant.now().plus(Duration.ofHours(24)))));

        service.requestDeletion(caller);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(
                com.marketplace.shared.api.AccountDeletionRequestedEvent.class);
        assertThat(((com.marketplace.shared.api.AccountDeletionRequestedEvent) event.getValue())
                .deletionToken()).isEqualTo("raw-token");
    }

    @Test
    void requestDeletionRefusesAnUnknownAccount() {
        when(userRepository.findBySubject(SUBJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestDeletion(caller))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(authActionTokenService, never()).issue(any(), any(), any());
    }

    @Test
    void deleteByTokenRedeemsThroughTheStandingErasure() {
        AuthActionToken right = AuthActionToken.issue(SUBJECT,
                AuthActionTokenPurpose.ACCOUNT_DELETION, "hash",
                Instant.now().plus(Duration.ofHours(24)));
        when(authActionTokenService.accountOf("the-token",
                AuthActionTokenPurpose.ACCOUNT_DELETION)).thenReturn(Optional.of(SUBJECT));
        when(authActionTokenService.consume("the-token",
                AuthActionTokenPurpose.ACCOUNT_DELETION)).thenReturn(right);
        when(userRepository.findBySubject(SUBJECT)).thenReturn(Optional.of(userRow));

        service.deleteByToken(caller, "the-token");

        verify(userService).pseudonymizeAccount(userRow.getId(),
                "account self-deletion (token redemption)", "self");
    }

    @Test
    void deleteByTokenRefusesAForeignRightWithoutBurningIt() {
        // The pinned Saleor 3.23.45 contract binds the redemption to the
        // authenticated caller (try_generators(user=..., token=...)): a
        // caller presenting another account's one-time right is refused
        // BEFORE the single-use consumption — the mismatch is the
        // caller's error, never the owner's burned right.
        when(authActionTokenService.accountOf("the-token",
                AuthActionTokenPurpose.ACCOUNT_DELETION))
                .thenReturn(Optional.of("someone-else@example.com"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.deleteByToken(caller, "the-token"))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);

        verify(authActionTokenService, never()).consume(any(), any());
        verify(userService, never()).pseudonymizeAccount(any(), any(), any());
    }

    @Test
    void changePasswordVerifiesRotatesAndKillsTheAuthorizations() {
        when(userDetailsManager.loadUserByUsername(SUBJECT))
                .thenReturn((UserDetails) org.springframework.security.core.userdetails.User
                        .withUsername(SUBJECT).password("{bcrypt}old")
                        .authorities("ROLE_CONSUMER").build());
        when(encoder.getObject()).thenReturn(passwordEncoder);
        when(passwordEncoder.matches("old", "{bcrypt}old")).thenReturn(true);
        when(passwordEncoder.encode("new-password-1")).thenReturn("{bcrypt}new");
        // The 2026-11-05 #810 round review: the rotation is a
        // verify-and-swap — the CAS write answers ONE row (the verified
        // old hash matched).
        when(jdbcTemplate.update(any(String.class), any(), any(), any()))
                .thenAnswer(invocation -> 1);

        service.changePassword(caller, "old", "new-password-1");

        // Rows 22 + 24 (agent findings 2026-10-26 14:33 export): the
        // PASSWORD COLUMN ONLY — never the updateUser snapshot replay
        // (the pre-fix replay bound the verification-time enabled flag and
        // authorities into the write) — plus the AFTER_COMMIT rotation
        // fact that kills the surviving sessions.
        // The verify-and-swap predicate carries the VERIFIED old hash —
        // a concurrent rotation answers zero rows and this call fails
        // instead of overwriting the winner's hash.
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.eq("UPDATE auth_users SET password = ?"
                        + " WHERE username = ? AND password = ?"),
                org.mockito.ArgumentMatchers.eq("{bcrypt}new"),
                org.mockito.ArgumentMatchers.eq(SUBJECT),
                org.mockito.ArgumentMatchers.eq("{bcrypt}old"));
        verify(userDetailsManager, never()).updateUser(any());
        verify(jdbcTemplate).update(
                "DELETE FROM oauth2_authorization WHERE principal_name = ?", SUBJECT);
        verify(eventPublisher).publishEvent(
                new com.marketplace.shared.api.AccountCredentialsRotated(SUBJECT));
    }

    @Test
    void changePasswordRefusesAWrongOldSecret() {
        when(userDetailsManager.loadUserByUsername(SUBJECT))
                .thenReturn((UserDetails) org.springframework.security.core.userdetails.User
                        .withUsername(SUBJECT).password("{bcrypt}old")
                        .authorities("ROLE_CONSUMER").build());
        when(encoder.getObject()).thenReturn(passwordEncoder);
        when(passwordEncoder.matches("wrong", "{bcrypt}old")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(caller, "wrong", "new-password-1"))
                .isInstanceOf(BadCredentialsException.class);
        verify(userDetailsManager, never()).updateUser(any(UserDetails.class));
    }

    @Test
    void changePasswordEnforcesTheShapeContract() {
        assertThatThrownBy(() -> service.changePassword(caller, "old", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 72 bytes");
        assertThatThrownBy(() -> service.changePassword(caller, null, "new-password-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current password is required");
    }

    @Test
    void changePasswordRefusesAMissingLoginRow() {
        when(userDetailsManager.loadUserByUsername(SUBJECT))
                .thenThrow(new UsernameNotFoundException("none"));

        assertThatThrownBy(() -> service.changePassword(caller, "old", "new-password-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
    /**
     * The 2026-11-08 #810 round-5 test nitpick: a CAS update answering ZERO
     * rows (a concurrent rotation won between the verification and the
     * swap) refuses with the optimistic-locking failure — and never
     * invalidates rights, deletes authorizations or publishes the rotation
     * event for a write that did not land.
     */
    @Test
    void changePasswordRefusesWhenTheCasUpdateMatchesNoRow() {
        when(userDetailsManager.loadUserByUsername(SUBJECT))
                .thenReturn((UserDetails) org.springframework.security.core.userdetails.User
                        .withUsername(SUBJECT).password("{bcrypt}old")
                        .authorities("ROLE_CONSUMER").build());
        when(encoder.getObject()).thenReturn(passwordEncoder);
        when(passwordEncoder.matches("old", "{bcrypt}old")).thenReturn(true);
        when(passwordEncoder.encode("new-password-1")).thenReturn("{bcrypt}new");
        when(jdbcTemplate.update(any(String.class), any(), any(), any()))
                .thenAnswer(invocation -> 0);

        assertThatThrownBy(() -> service.changePassword(caller, "old", "new-password-1"))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);

        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.eq("DELETE FROM oauth2_authorization"
                        + " WHERE principal_name = ?"),
                org.mockito.ArgumentMatchers.anyString());
        verify(eventPublisher, never()).publishEvent(any(
                com.marketplace.shared.api.AccountCredentialsRotated.class));
    }

    /**
     * The 2026-11-08 #810 round-6 review (Minor · Security & Privacy): the
     * verify-then-rotate window rides the per-user advisory auth-state
     * lock — the rotation must serialize with the email-change request's
     * verify-then-issue window, or an EMAIL_CHANGE right minted under the
     * OLD password could outlive this rotation's invalidateOutstanding.
     */
    @Test
    void changePasswordTakesTheAuthStateLockBeforeTheVerification() {
        when(userRepository.findBySubject(SUBJECT)).thenReturn(Optional.of(userRow));
        when(userDetailsManager.loadUserByUsername(SUBJECT))
                .thenReturn((UserDetails) org.springframework.security.core.userdetails.User
                        .withUsername(SUBJECT).password("{bcrypt}old")
                        .authorities("ROLE_CONSUMER").build());
        when(encoder.getObject()).thenReturn(passwordEncoder);
        when(passwordEncoder.matches("old", "{bcrypt}old")).thenReturn(true);
        when(passwordEncoder.encode("new-password-1")).thenReturn("{bcrypt}new");
        when(jdbcTemplate.update(any(String.class), any(), any(), any()))
                .thenAnswer(invocation -> 1);

        service.changePassword(caller, "old", "new-password-1");

        // The serialization point: the account row resolves, the advisory
        // lock lands BEFORE the login load and verification, and the
        // outstanding-right kill rides inside the same serialized window.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(userRepository,
                userDetailsManager, authActionTokenService);
        order.verify(userRepository).findBySubject(SUBJECT);
        order.verify(userRepository).lockUserAuth(userRow.getId().toString());
        order.verify(userDetailsManager).loadUserByUsername(SUBJECT);
        order.verify(authActionTokenService).invalidateOutstanding(SUBJECT);
    }

}
