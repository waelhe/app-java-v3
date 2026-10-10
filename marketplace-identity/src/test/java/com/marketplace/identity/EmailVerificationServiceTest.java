package com.marketplace.identity;

import com.marketplace.shared.api.EmailVerificationRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-04 unit guards — the email-verification state model (A.2): the LATEST
 * EMAIL_VERIFICATION token row IS the account's verification state (no row =
 * grandfathered; live = pending; consumed = finished — owner-completed or
 * administratively invalidated), the resend guard that re-arms ONLY a
 * pending account, and the redemption leg's hold-lifting write shape.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String EMAIL = "member@example.com";
    private static final Duration TTL = Duration.ofHours(24);

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetailsManager userDetailsManager;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuthActionTokenService authActionTokenService;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(userRepository, userDetailsManager, eventPublisher,
                authActionTokenService,
                new IdentityMailProperties("http://localhost:3000", Duration.ofMinutes(30), TTL,
                        Duration.ofSeconds(60)));
    }

    private User member() {
        return User.create(EMAIL, EMAIL, "Member", UserRole.CONSUMER);
    }

    private AuthActionToken verificationToken(boolean consumed) {
        AuthActionToken token = AuthActionToken.issue(
                EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION, "e".repeat(64), NOW.plus(TTL));
        if (consumed) {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    token, "consumedAt", NOW.minus(Duration.ofMinutes(1)));
        }
        return token;
    }

    @Test
    void issueFor_aKnownSubjectPublishesTheMailEventWithTheDeepLink() {
        User user = member();
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(user));
        when(authActionTokenService.issue(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "raw-verification-token", NOW.plus(TTL))));

        boolean published = service.issueFor(EMAIL);

        assertThat(published).isTrue();
        ArgumentCaptor<EmailVerificationRequestedEvent> event =
                ArgumentCaptor.forClass(EmailVerificationRequestedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(user.getId());
        assertThat(event.getValue().verificationLink())
                .isEqualTo("http://localhost:3000/verify-email?token=raw-verification-token");
        assertThat(event.getValue().expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void issueFor_aSubjectWithoutADomainRowAnswersFalseAndPublishesNothing() {
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.empty());

        assertThat(service.issueFor(EMAIL)).isFalse();
        verifyNoInteractions(authActionTokenService, eventPublisher);
    }

    @Test
    void resend_aPendingAccountIsReArmed() {
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(false)));
        when(userRepository.findBySubject(EMAIL)).thenReturn(Optional.of(member()));
        when(authActionTokenService.issue(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION, TTL))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "fresh-token", NOW.plus(TTL))));

        service.resend(EMAIL);

        // A live (unconsumed — expired or not) latest row means PENDING: the
        // resend mints the fresh right and publishes the mail event.
        verify(eventPublisher).publishEvent(any(EmailVerificationRequestedEvent.class));
    }

    @Test
    void resend_aConsumedLatestRowIsSkipped_theBanAndFinishedStatesRefuse() {
        // The invariant's own guard: a consumed latest row means finished —
        // the owner completed verification, or an administrative surface
        // consumed the outstanding right. Either way, no fresh redemption
        // right is minted through this surface.
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(verificationToken(true)));

        service.resend(EMAIL);

        verify(authActionTokenService, never()).issue(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(EmailVerificationRequestedEvent.class));
    }

    @Test
    void resend_noTokenRowAtAllMeansGrandfathered_nothingToResend() {
        when(userDetailsManager.userExists(EMAIL)).thenReturn(true);
        when(authActionTokenService.latest(EMAIL, AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.empty());

        service.resend(EMAIL);

        verify(authActionTokenService, never()).issue(any(), any(), any());
    }

    @Test
    void resend_anUnknownAddressIsASilentNoOp() {
        when(userDetailsManager.userExists(EMAIL)).thenReturn(false);

        service.resend(EMAIL);

        verifyNoInteractions(authActionTokenService, eventPublisher);
    }

    @Test
    void completeVerification_liftsTheHoldAndReplaysEveryOtherStoredFlag() {
        AuthActionToken token = verificationToken(false);
        when(authActionTokenService.consume("raw-token", AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(token);
        when(userDetailsManager.loadUserByUsername(EMAIL)).thenReturn(
                org.springframework.security.core.userdetails.User.withUsername(EMAIL)
                        .password("{bcrypt}$2a$stored-verifier").authorities("ROLE_CONSUMER")
                        .disabled(true).build());

        service.completeVerification("raw-token");

        ArgumentCaptor<UserDetails> updated = ArgumentCaptor.forClass(UserDetails.class);
        verify(userDetailsManager).updateUser(updated.capture());
        // The hold lifts: enabled=true; the encoded password is replayed
        // VERBATIM (the verification flow never touches the secret); the
        // authority survives.
        assertThat(updated.getValue().isEnabled()).isTrue();
        assertThat(updated.getValue().getPassword()).isEqualTo("{bcrypt}$2a$stored-verifier");
        assertThat(updated.getValue().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_CONSUMER"));
    }
}
