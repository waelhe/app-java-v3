package com.marketplace.identity;

import com.marketplace.shared.api.PasswordResetRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-04 unit guards — the password-reset journey's contracts (A.1): the
 * enumeration-safe request leg (the OWASP measured line: one constant
 * behavior for every address, the mail leg alone differs), and the
 * redemption leg's official write shape (the delegating encoder's
 * {@code {bcrypt}} store, the framework manager's flag-preserving
 * {@code updateUser}, and the L23 authorization kill).
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String EMAIL = "member@example.com";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetailsManager userDetailsManager;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuthActionTokenService authActionTokenService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ObjectProvider<org.springframework.security.crypto.password.PasswordEncoder> passwordEncoder;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, userDetailsManager, eventPublisher,
                authActionTokenService,
                new IdentityMailProperties("http://localhost:3000", TTL, Duration.ofHours(24),
                        Duration.ofSeconds(60)),
                jdbcTemplate, passwordEncoder);
        org.mockito.Mockito.lenient().when(passwordEncoder.getObject()).thenReturn(
                org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }

    private User member() {
        return User.create(EMAIL, EMAIL, "Member", UserRole.CONSUMER);
    }

    @Test
    void requestReset_anAccountWithALoginRowIssuesTheTokenAndPublishesTheMailEvent() {
        User user = member();
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        when(authActionTokenService.issue(EMAIL, AuthActionTokenPurpose.PASSWORD_RESET, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "raw-one-time-token", NOW.plus(TTL))));

        service.requestReset(EMAIL);

        // The deep link is the mail's own content: the configured client
        // origin + the reset route + the raw one-time secret.
        ArgumentCaptor<PasswordResetRequestedEvent> event =
                ArgumentCaptor.forClass(PasswordResetRequestedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(user.getId());
        assertThat(event.getValue().displayName()).isEqualTo("Member");
        assertThat(event.getValue().resetLink())
                .isEqualTo("http://localhost:3000/reset-password?token=raw-one-time-token");
        assertThat(event.getValue().expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void requestReset_anUnknownAddressIsASilentNoOp() {
        // The enumeration wall: no login row, no issuance, no event — the
        // caller still answers the constant 202 the controller pins.
        when(userDetailsManager.userExists(EMAIL)).thenReturn(false);

        service.requestReset(EMAIL);

        verifyNoInteractions(userRepository, authActionTokenService, eventPublisher);
    }

    @Test
    void requestReset_anOidcOnlyAccountHasNoLoginRowAndTakesTheSameSilentPath() {
        // The OIDC account's password lives with its identity provider —
        // the login store's own userExists is the truth the flow consults.
        when(userDetailsManager.userExists(EMAIL)).thenReturn(false);

        service.requestReset(EMAIL);

        verifyNoInteractions(authActionTokenService, eventPublisher);
    }

    @Test
    void requestReset_aThrottledReissueSendsNothing() {
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(member()));
        when(authActionTokenService.issue(EMAIL, AuthActionTokenPurpose.PASSWORD_RESET, TTL))
                .thenReturn(Optional.empty());

        service.requestReset(EMAIL);

        // The 60-second per-account floor: no second mail inside the window.
        verify(eventPublisher, never())
                .publishEvent(org.mockito.ArgumentMatchers.any(PasswordResetRequestedEvent.class));
    }

    @Test
    void requestReset_aLoginRowWithoutADomainRowIsADriftNoOp() {
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.empty());

        service.requestReset(EMAIL);

        // The register flow's single-transaction impossibility — a drift
        // never becomes a 500 probe surface; nothing is issued.
        verifyNoInteractions(authActionTokenService, eventPublisher);
    }

    @Test
    void completeReset_encodesTheNewSecretPreservesEveryFlagAndKillsTheAuthorizations() {
        AuthActionToken token = AuthActionToken.issue(
                EMAIL, AuthActionTokenPurpose.PASSWORD_RESET, "c".repeat(64), NOW.plus(TTL));
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(token);
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("{noop}old-secret").authorities("ROLE_CONSUMER")
                        .build());

        service.completeReset("raw-token", "the-new-password");

        ArgumentCaptor<UserDetails> updated = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(updated.capture());
        // The official store: the NEW secret in the delegating encoder's
        // {bcrypt} form — never the clear value; every other flag replayed.
        assertThat(updated.getValue().getPassword()).startsWith("{bcrypt}");
        assertThat(updated.getValue().getPassword()).doesNotContain("the-new-password");
        assertThat(updated.getValue().isEnabled()).isTrue();
        assertThat(updated.getValue().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_CONSUMER"));
        // The L23 kill: a refresh token minted under the old password does
        // not survive the rotation.
        verify(jdbcTemplate).update(
                eq(PasswordResetService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL), eq(EMAIL));
    }

    @Test
    void completeReset_aDisabledAccountKeepsItsDisabledFlag() {
        // The reset redeems a token, not a ban: an administratively disabled
        // account gets a new password but STAYS disabled (the
        // ban-vs-verification invariant's other face — only the verification
        // surface lifts the verification hold, and only the administrative
        // surface lifts a ban).
        AuthActionToken token = AuthActionToken.issue(
                EMAIL, AuthActionTokenPurpose.PASSWORD_RESET, "d".repeat(64), NOW.plus(TTL));
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(token);
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("{noop}old-secret").authorities("ROLE_CONSUMER")
                        .disabled(true).build());

        service.completeReset("raw-token", "the-new-password");

        ArgumentCaptor<UserDetails> updated = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(updated.capture());
        assertThat(updated.getValue().isEnabled())
                .as("the reset never re-enables a disabled account").isFalse();
    }

    private static String eq(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
