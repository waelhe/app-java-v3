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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    /**
     * Row 20 (agent findings 2026-10-26 14:33 export, CWE-521): the shared
     * password policy is enforced INSIDE completeReset — before the
     * single-use token is consumed. The GraphQL setPassword transport
     * checks for blank input alone, so a too-short replacement could
     * previously be written through this path; the refusal leaves the
     * token LIVE (the honest retry contract: nothing half-spent).
     */
    @Test
    void completeReset_enforcesTheSharedPasswordPolicyBeforeConsumingTheToken() {
        assertThatThrownBy(() -> service.completeReset("raw-token", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The new password must be 8 to 72 characters long and at most 72 UTF-8 bytes");
        assertThatThrownBy(() -> service.completeReset("raw-token", null))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verify(authActionTokenService, org.mockito.Mockito.never())
                .consume(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeReset_measuresThePolicysOwnLengthNotARegexShape() {
        // Review round (CodeRabbit on the 4a split, 2026-10-28): the policy
        // measures String.length() (UTF-16 code units) — the same count the
        // request DTO's @Size applies. The first shape's regex rejected both
        // values below (the dot excludes line terminators and counts code
        // points); both are policy-valid and must complete the reset.
        AuthActionToken token = AuthActionToken.issue(
                EMAIL, AuthActionTokenPurpose.PASSWORD_RESET, "c".repeat(64), NOW.plus(TTL));
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(token);
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("{noop}old-secret").authorities("ROLE_CONSUMER")
                        .build());

        // 8 UTF-16 units including a line feed — policy-valid by the
        // request's own measure.
        service.completeReset("raw-token", "abc\ndefg");

        // A four-emoji password: 16 UTF-16 units, 32 UTF-8 bytes — within
        // both the @Size bounds and the bcrypt byte ceiling.
        AuthActionToken secondToken = AuthActionToken.issue(
                EMAIL, AuthActionTokenPurpose.PASSWORD_RESET, "d".repeat(64), NOW.plus(TTL));
        when(authActionTokenService.consume("raw-two", AuthActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(secondToken);
        service.completeReset("raw-two", "\uD83D\uDE00".repeat(4));
    }

    @Test
    void completeReset_refusesAnUnhashableByteLengthBeforeConsumingTheToken() {
        // Review round (CodeRabbit on the #757 sync, 2026-10-29): 71 UTF-16
        // units built from surrogate pairs carry 142 UTF-8 bytes — inside the
        // @Size count, past Spring Security's bcrypt 72-byte working buffer.
        // The honest 400 precedes the single-use token's consumption, so the
        // token stays live for an honest retry.
        String unhashable = "\uD83D\uDE00".repeat(35) + "x";
        org.assertj.core.api.Assertions.assertThat(unhashable).hasSize(71);
        assertThatThrownBy(() -> service.completeReset("raw-token", unhashable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The new password must be 8 to 72 characters long and at most 72 UTF-8 bytes");
        verify(authActionTokenService, never()).consume(any(), any());
    }

    @Test
    void completeReset_writesThePasswordColumnOnlyAndKillsTheAuthorizations() {
        AuthActionToken token = AuthActionToken.issue(
                EMAIL, AuthActionTokenPurpose.PASSWORD_RESET, "c".repeat(64), NOW.plus(TTL));
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(token);
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("{noop}old-secret").authorities("ROLE_CONSUMER")
                        .build());

        service.completeReset("raw-token", "the-new-password");

        // Row 46 (agent findings 2026-10-26 — CWE-362): the credential write
        // is the PASSWORD COLUMN ONLY. The pre-fix flow replayed a full
        // updateUser from the loaded snapshot — the framework deletes the
        // CURRENT auth_authorities rows and re-inserts the snapshot's, so an
        // admin's role removal landing around the encoding was silently
        // restored. Now: NO updateUser call at all, and the single column
        // write carries the NEW secret in the delegating encoder's {bcrypt}
        // form — never the clear value.
        verify(userDetailsManager, never()).updateUser(any(UserDetails.class));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(),
                org.mockito.ArgumentMatchers.argThat((Object arg) -> arg instanceof String encoded
                        && encoded.startsWith("{bcrypt}")
                        && !encoded.contains("the-new-password")),
                eq(EMAIL));
        assertThat(sql.getValue()).isEqualTo(PasswordResetService.UPDATE_PASSWORD_ONLY);
        // The L23 kill: a refresh token minted under the old password does
        // not survive the rotation.
        verify(jdbcTemplate).update(
                eq(PasswordResetService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL), eq(EMAIL));
        // CodeRabbit agent findings, row 14 / CWE-613 — the surviving-session
        // kill: the rotation fact rides the house R8 channel so
        // AccountStatusSessionInvalidator expires every session of the
        // account AFTER_COMMIT (a pre-reset cookie must not re-mint the
        // grants this recovery just revoked).
        ArgumentCaptor<com.marketplace.shared.api.AccountCredentialsRotated> rotated =
                ArgumentCaptor.forClass(com.marketplace.shared.api.AccountCredentialsRotated.class);
        verify(eventPublisher).publishEvent(rotated.capture());
        assertThat(rotated.getValue().username()).isEqualTo(EMAIL);
    }

    @Test
    void completeReset_aDisabledAccountIsNeverTouchedByTheCredentialRotation() {
        // Row 46's other face: the reset redeems a token, not a ban — an
        // administratively disabled account gets a new password and STAYS
        // disabled. The pre-fix snapshot replay made this an assertion over
        // the replayed flags; the password-only write makes it structural:
        // the flags live in their own columns and the reset's single-column
        // UPDATE cannot reach them (the ban-vs-verification invariant's
        // other face — only the administrative surface lifts a ban).
        AuthActionToken token = AuthActionToken.issue(
                EMAIL, AuthActionTokenPurpose.PASSWORD_RESET, "d".repeat(64), NOW.plus(TTL));
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(token);
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("{noop}old-secret").authorities("ROLE_CONSUMER")
                        .disabled(true).build());

        service.completeReset("raw-token", "the-new-password");

        verify(userDetailsManager, never()).updateUser(any(UserDetails.class));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.startsWith("UPDATE auth_users SET password"),
                org.mockito.ArgumentMatchers.anyString(), eq(EMAIL));
    }

    private static String eq(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
