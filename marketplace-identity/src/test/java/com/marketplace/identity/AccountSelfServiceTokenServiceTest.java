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
import java.util.UUID;

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
        when(authActionTokenService.consume("the-token",
                AuthActionTokenPurpose.ACCOUNT_DELETION)).thenReturn(right);
        when(userRepository.findBySubject(SUBJECT)).thenReturn(Optional.of(userRow));

        service.deleteByToken("the-token");

        verify(userService).pseudonymizeAccount(userRow.getId(),
                "account self-deletion (token redemption)", "self");
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

        service.changePassword(caller, "old", "new-password-1");

        // Rows 22 + 24 (agent findings 2026-10-26 14:33 export): the
        // PASSWORD COLUMN ONLY — never the updateUser snapshot replay
        // (the pre-fix replay bound the verification-time enabled flag and
        // authorities into the write) — plus the AFTER_COMMIT rotation
        // fact that kills the surviving sessions.
        verify(jdbcTemplate).update(PasswordResetService.UPDATE_PASSWORD_ONLY,
                "{bcrypt}new", SUBJECT);
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
                .hasMessageContaining("8 to 72 characters");
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
}
