package com.marketplace.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the lifecycle adapter's
 * delegation guards — most importantly the {@code accountUpdate} leg's
 * transactional hand-off (B9: the adapter delegates to the SERVICE's own
 * write method, never the cached/detached read's mutated copy).
 */
@ExtendWith(MockitoExtension.class)
class AccountLifecycleSpiAdapterTest {

    @Mock
    private PasswordResetService passwordResetService;
    @Mock
    private EmailVerificationService emailVerificationService;
    @Mock
    private AccountEmailChangeService emailChangeService;
    @Mock
    private AccountSelfServiceTokenService selfServiceTokenService;
    @Mock
    private UserService userService;
    @Mock
    private TokenRevocationService tokenRevocationService;
    @Mock
    private AuthActionTokenService authActionTokenService;

    private AccountLifecycleSpiAdapter adapter;

    private Authentication caller;

    @BeforeEach
    void setUp() {
        adapter = new AccountLifecycleSpiAdapter(passwordResetService, emailVerificationService,
                emailChangeService, selfServiceTokenService, userService, tokenRevocationService,
                authActionTokenService);
        caller = new UsernamePasswordAuthenticationToken("member@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
    }

    @Test
    void updateOwnProfileDelegatesToTheTransactionalWrite() {
        // B9: the adapter hands the subject + display name to UserService's
        // OWN write method — the previous one-liner mutated a cached,
        // detached copy whose changes never reached the store.
        adapter.updateOwnProfile(caller, "New Display");

        verify(userService).updateOwnProfileBySubject("member@example.com", "New Display");
    }

    @Test
    void registerDelegatesToTheStandingFlow() {
        when(userService.register("new@example.com", "password123", "Newbie"))
                .thenReturn(User.create("new@example.com", "new@example.com", "Newbie",
                        UserRole.CONSUMER));

        adapter.register("new@example.com", "password123", "Newbie");

        verify(userService).register("new@example.com", "password123", "Newbie");
    }

    @Test
    void theRedemptionLegsDelegateToTheirOwners() {
        // The 2026-11-06 #806 round review (Major · Security & Privacy):
        // the email-change redemption carries the authenticated CALLER —
        // the pinned Saleor 3.23.45 contract binds the redemption to the
        // caller's identity, and the adapter passes it through unchanged.
        org.springframework.security.core.Authentication caller =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "member@example.com", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_CONSUMER")));
        adapter.confirmEmailChange(caller, "change-token");
        verify(emailChangeService).confirmChange(caller, "change-token");

        adapter.requestPasswordReset("member@example.com");
        verify(passwordResetService).requestReset("member@example.com");

        adapter.confirmAccount("member@example.com", "verify-token");
        verify(emailVerificationService).completeVerification("verify-token");

        adapter.completePasswordReset("member@example.com", "reset-token", "new-password");
        verify(passwordResetService).completeReset("reset-token", "new-password");
    }

    @Test
    void theTokenOwnershipComparisonIsCaseInsensitiveOnBothSides() {
        // The 2026-11-06 session-9 #806 final review (Minor · Functional
        // Correctness): the REST registration path stores the subject
        // verbatim (mixed case) while the GraphQL accountRegister
        // lowercases — the CORRECT owner was falsely rejected whenever
        // the two sides normalized differently. The ownership verdict is
        // case-insensitive; the redemption still reaches its owner.
        when(authActionTokenService.accountOf("verify-token",
                com.marketplace.identity.AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(java.util.Optional.of("Member@Example.com"));

        adapter.confirmAccount("member@example.com", "verify-token");
        verify(emailVerificationService).completeVerification("verify-token");

        // And the mirrored direction: a lowercased owner, a mixed-case
        // caller — the valid link redeems either way.
        when(authActionTokenService.accountOf("reset-token",
                com.marketplace.identity.AuthActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(java.util.Optional.of("member@example.com"));

        adapter.completePasswordReset("  Member@Example.COM ", "reset-token", "new-password");
        verify(passwordResetService).completeReset("reset-token", "new-password");
    }

    @Test
    void aKnownTokenOfADifferentAccountIsStillRefused() {
        // The case-insensitive verdict is still an OWNERSHIP verdict: a
        // known token minted for another account answers the classified
        // bad-request refusal HERE and the owning service's consume never
        // runs.
        when(authActionTokenService.accountOf("verify-token",
                com.marketplace.identity.AuthActionTokenPurpose.EMAIL_VERIFICATION))
                .thenReturn(java.util.Optional.of("other@example.com"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adapter.confirmAccount("member@example.com", "verify-token"))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessage("This link is not valid for the identified account");
        verify(emailVerificationService, org.mockito.Mockito.never())
                .completeVerification(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void theTokenLegsDelegateToTheirOwners() {
        adapter.deactivateAllTokens(caller);
        verify(tokenRevocationService).deactivateAllFor(caller);

        adapter.deleteAccountByToken(caller, "deletion-token");
        verify(selfServiceTokenService).deleteByToken(caller, "deletion-token");

        adapter.requestAccountDeletion(caller);
        verify(selfServiceTokenService).requestDeletion(caller);
    }
    /**
     * The 2026-11-08 #810 round-5 review (Minor · Security & Privacy): a
     * redemption that names NO account refuses outright — the old
     * early-return let a valid token (a bare capability) activate the
     * account or rotate the password with no ownership comparison at all.
     * Both redemption legs carry the same refusal.
     */
    @Test
    void confirmAccountRefusesAMissingEmailAddress() {
        assertThatThrownBy(() -> adapter.confirmAccount(null, "some-token"))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("not valid for the identified account");
        verify(emailVerificationService, never()).completeVerification(any());
    }

    @Test
    void passwordResetRedemptionRefusesAMissingEmailAddress() {
        assertThatThrownBy(() -> adapter.completePasswordReset(null, "some-token", "new-pass-1"))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("not valid for the identified account");
        verify(passwordResetService, never()).completeReset(any(), any());
    }

    @Test
    void aMissingTokenStillFallsThroughToTheOwningServiceVocabulary() {
        // The token==null leg stays untouched: this guard never invented a
        // competing vocabulary — the delegation proceeds and the owning
        // service's own honest error for the absent token answers there.
        adapter.confirmAccount("member@example.com", null);

        verify(emailVerificationService).completeVerification(null);
    }

}
