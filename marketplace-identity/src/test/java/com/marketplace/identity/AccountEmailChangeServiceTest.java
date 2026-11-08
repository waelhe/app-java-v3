package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the email-change journey's
 * owning guards — the request leg's case-normalized target (m5) and the
 * confirm leg's complete login-identity move (B8: subject AND email move
 * together, the V112 token history purges before the login row dies, and
 * the old principal's authorizations + caches follow).
 */
@ExtendWith(MockitoExtension.class)
class AccountEmailChangeServiceTest {

    private static final String OLD_SUBJECT = "member@example.com";
    private static final String NEW_EMAIL = "newperson@example.com";

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
    private ApplicationEventPublisher eventPublisher;

    private AccountEmailChangeService service;

    private Authentication caller;
    private User userRow;

    @BeforeEach
    void setUp() {
        service = new AccountEmailChangeService(userRepository, userDetailsManager,
                authActionTokenService, userService, encoder, eventPublisher);
        caller = new UsernamePasswordAuthenticationToken(OLD_SUBJECT, null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
        userRow = User.create(OLD_SUBJECT, OLD_SUBJECT, "Member", UserRole.CONSUMER);
        lenient().when(encoder.getObject()).thenReturn(passwordEncoder);
    }

    private UserDetails loginRow() {
        return org.springframework.security.core.userdetails.User.withUsername(OLD_SUBJECT)
                .password("{bcrypt}hash")
                .authorities("ROLE_CONSUMER")
                .build();
    }

    private AuthActionToken redeemedToken() {
        return AuthActionToken.issue(OLD_SUBJECT, AuthActionTokenPurpose.EMAIL_CHANGE,
                "hash", Instant.now().plus(Duration.ofMinutes(30)));
    }

    @Test
    void theRequestLegNormalizesTheTargetToTheLowercaseKey() {
        when(userDetailsManager.loadUserByUsername(OLD_SUBJECT)).thenReturn(loginRow());
        when(passwordEncoder.matches("pw", "{bcrypt}hash")).thenReturn(true);
        when(userRepository.findBySubject(OLD_SUBJECT)).thenReturn(Optional.of(userRow));
        when(authActionTokenService.issue(any(), any(), any()))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "raw-token", Instant.now().plus(Duration.ofMinutes(30)))));

        service.requestChange(caller, "  NewPerson@Example.COM ", "pw");

        // m5: the stored pending pair and the mail event both carry the
        // LOWERCASED address — the case-insensitive account-identifier
        // discipline the admin path already enforces (User@x.com and
        // user@x.com are ONE account).
        assertThat(userRow.consumePendingEmailChange()).isEqualTo("newperson@example.com");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(
                com.marketplace.shared.api.EmailChangeRequestedEvent.class);
        assertThat(((com.marketplace.shared.api.EmailChangeRequestedEvent) event.getValue())
                .newEmail()).isEqualTo("newperson@example.com");
    }

    /**
     * B22 (IDP-04 — the 2026-11-03 adversarial review, review-pr-806): a
     * THROTTLED re-request must not move the pending pair. The silent
     * throttle (an enumeration-safe no-op that sends nothing) previously
     * still overwrote the user's pending target — the STILL LIVE prior
     * link then confirmed and applied the address it was never issued
     * for (two sequential requests A then B, the second inside
     * minIssueInterval: link A confirmed and applied B). The pair moves
     * hand-in-hand with the live link now: the redeemed right always
     * applies the pair it was issued for, literally.
     */
    @Test
    void aThrottledReRequestKeepsThePendingPairBoundToTheLiveLink() {
        when(userDetailsManager.loadUserByUsername(OLD_SUBJECT)).thenReturn(loginRow());
        when(passwordEncoder.matches("pw", "{bcrypt}hash")).thenReturn(true);
        when(userRepository.findBySubject(OLD_SUBJECT)).thenReturn(Optional.of(userRow));
        // The first request issues link A for target A; the re-request
        // hits the throttle window — the documented silent no-op.
        when(authActionTokenService.issue(any(), any(), any()))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "link-a", Instant.now().plus(Duration.ofMinutes(30)))))
                .thenReturn(Optional.empty());

        service.requestChange(caller, "a-person@example.com", "pw");
        service.requestChange(caller, "b-person@example.com", "pw");

        // The throttled re-request sent nothing and moved NOTHING: the
        // live link A stays bound to A — never B.
        assertThat(userRow.consumePendingEmailChange()).isEqualTo("a-person@example.com");
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    /**
     * B22's other half: a re-request that DOES issue moves the pair with
     * the new link — issue()'s own replacement rule (the V112 live-token
     * unique index) consumes the prior right, and the pending pair
     * follows the newest live link exactly.
     */
    @Test
    void aReIssuingRequestMovesThePairHandInHandWithTheNewLink() {
        when(userDetailsManager.loadUserByUsername(OLD_SUBJECT)).thenReturn(loginRow());
        when(passwordEncoder.matches("pw", "{bcrypt}hash")).thenReturn(true);
        when(userRepository.findBySubject(OLD_SUBJECT)).thenReturn(Optional.of(userRow));
        when(authActionTokenService.issue(any(), any(), any()))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "link-a", Instant.now().plus(Duration.ofMinutes(30)))))
                .thenReturn(Optional.of(new AuthActionTokenService.IssuedToken(
                        "link-b", Instant.now().plus(Duration.ofMinutes(30)))));

        service.requestChange(caller, "a-person@example.com", "pw");
        service.requestChange(caller, "b-person@example.com", "pw");

        // The pair follows the NEWEST live link — link B carries B.
        assertThat(userRow.consumePendingEmailChange()).isEqualTo("b-person@example.com");
        verify(eventPublisher, times(2)).publishEvent(any(Object.class));
    }

    /**
     * The 2026-11-08 #810 round-6 review (Minor · Security & Privacy): the
     * verification rides the per-user advisory auth-state lock — the
     * verify-then-issue window must serialize with every secret rotation
     * (the rotation's invalidateOutstanding must not run between this
     * leg's password verification and its token issue, or the minted right
     * would outlive the secret regime that authorized it). The lock is
     * taken BEFORE the login row is loaded; the unverifiable caller still
     * answers the classified BadCredentials, and never reaches the issue.
     */
    @Test
    void theRequestLegVerifiesThePasswordUnderTheAuthStateLock() {
        when(userRepository.findBySubject(OLD_SUBJECT)).thenReturn(Optional.of(userRow));
        when(userDetailsManager.loadUserByUsername(OLD_SUBJECT)).thenReturn(loginRow());
        when(passwordEncoder.matches("wrong", "{bcrypt}hash")).thenReturn(false);

        assertThatThrownBy(() -> service.requestChange(caller, NEW_EMAIL, "wrong"))
                .isInstanceOf(BadCredentialsException.class);

        // The serialization point: the account row's id resolves first, the
        // advisory lock lands BEFORE the login load, and the issue never
        // runs for an unverifiable caller.
        InOrder order = inOrder(userRepository, userDetailsManager, authActionTokenService);
        order.verify(userRepository).findBySubject(OLD_SUBJECT);
        order.verify(userRepository).lockUserAuth(userRow.getId().toString());
        order.verify(userDetailsManager).loadUserByUsername(OLD_SUBJECT);
        verify(authActionTokenService, never()).issue(any(), any(), any());
    }

    @Test
    void theRequestLegRefusesAnAddressBeyondTheLoginStoresDomain() {
        // The round-2 CodeRabbit finding: the address becomes the login
        // username — auth_users.username caps at 50 characters (V13), and
        // the failure would otherwise surface at confirm time as a storage
        // error AFTER the mail leg, instead of the clean 400 at the request
        // boundary (before the password check and the token issue).
        String tooLong = "a".repeat(40) + "@example.com";

        // Row 17 (agent findings 2026-10-26 14:33 export, CWE-1333): the
        // domain bound now fires FIRST — the shape pattern is evaluated
        // only for an input already inside the 50-character domain, so the
        // oversized address is refused by the bound gate's own message
        // (the same clean 400, one gate earlier, before any backtracking
        // match work).
        assertThatThrownBy(() -> service.requestChange(caller, tooLong, "pw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A valid new email address is required");
        verify(userDetailsManager, never()).loadUserByUsername(anyString());
    }

    @Test
    void theConfirmLegMovesTheWholeLoginIdentityInOneTransaction() {
        userRow.requestEmailChange(NEW_EMAIL);
        when(authActionTokenService.accountOf("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.of(OLD_SUBJECT));
        when(authActionTokenService.consume("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(redeemedToken());
        when(userRepository.findBySubject(OLD_SUBJECT)).thenReturn(Optional.of(userRow));
        when(userRepository.existsBySubject(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.userExists(NEW_EMAIL)).thenReturn(false);
        when(userDetailsManager.loadUserByUsername(OLD_SUBJECT)).thenReturn(loginRow());

        String answered = service.confirmChange(caller, "the-token");

        assertThat(answered).isEqualTo(NEW_EMAIL);
        // B8: the subject moved TOGETHER with the email (the platform's
        // subject==username invariant).
        assertThat(userRow.getSubject()).isEqualTo(NEW_EMAIL);
        assertThat(userRow.getEmail()).isEqualTo(NEW_EMAIL);
        // The exact ordering: purge the V112 token history BEFORE the old
        // login row dies, then the shared aftermath.
        InOrder order = inOrder(authActionTokenService, userDetailsManager, userService);
        order.verify(userDetailsManager).createUser(any(UserDetails.class));
        order.verify(authActionTokenService).purgeHistory(OLD_SUBJECT);
        order.verify(userDetailsManager).deleteUser(OLD_SUBJECT);
        order.verify(userService).loginIdentityMoved(OLD_SUBJECT);
    }

    @Test
    void theConfirmLegRefusesATakenAddress() {
        userRow.requestEmailChange(NEW_EMAIL);
        when(authActionTokenService.accountOf("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.of(OLD_SUBJECT));
        when(authActionTokenService.consume("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(redeemedToken());
        when(userRepository.findBySubject(OLD_SUBJECT)).thenReturn(Optional.of(userRow));
        when(userRepository.existsBySubject(NEW_EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.confirmChange(caller, "the-token"))
                .isInstanceOf(ConflictException.class);
        verify(userDetailsManager, never()).deleteUser(any());
    }

    @Test
    void theConfirmLegRefusesARightWithoutAPendingPair() {
        when(authActionTokenService.accountOf("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.of(OLD_SUBJECT));
        when(authActionTokenService.consume("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(redeemedToken());
        when(userRepository.findBySubject(OLD_SUBJECT)).thenReturn(Optional.of(userRow));

        assertThatThrownBy(() -> service.confirmChange(caller, "the-token"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void theConfirmLegRefusesAnUnknownAccount() {
        when(authActionTokenService.accountOf("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.of(OLD_SUBJECT));
        when(authActionTokenService.consume("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(redeemedToken());
        when(userRepository.findBySubject(OLD_SUBJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmChange(caller, "the-token"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * The 2026-11-06 #806 round review (Major · Security &amp; Privacy):
     * the redemption is bound to the authenticated CALLER — the pinned
     * Saleor 3.23.45 contract resolves the user from the request context
     * and rejects any token whose owner is someone else. The mismatch
     * answers the classified conflict WITHOUT consuming the right: the
     * legitimate owner's one-time token survives the probe.
     */
    @Test
    void theConfirmLegRefusesARightOwnedByAnotherAccountWithoutConsumingIt() {
        when(authActionTokenService.accountOf("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.of("someone-else@example.com"));

        assertThatThrownBy(() -> service.confirmChange(caller, "the-token"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different account");
        // The right is NOT consumed — the owner can still redeem it.
        verify(authActionTokenService, never())
                .consume(anyString(), any(AuthActionTokenPurpose.class));
    }

    /**
     * The same round: an unknown token answers the SAME unknown-token
     * denial the consumption path would (the accountOf javadoc's own
     * contract — never a second, drift-prone message).
     */
    @Test
    void theConfirmLegAnswersAnUnknownTokenWithTheConsumptionPathDenial() {
        when(authActionTokenService.accountOf("the-token", AuthActionTokenPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmChange(caller, "the-token"))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("invalid or has already been used");
        verify(authActionTokenService, never())
                .consume(anyString(), any(AuthActionTokenPurpose.class));
    }
}
