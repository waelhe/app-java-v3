package com.marketplace.identity;

import com.marketplace.identity.spi.AccountLifecycleSpi;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * {@code AccountLifecycleSpi} adapter — one delegating façade over the
 * identity module's account-lifecycle use cases (the standing
 * reset/confirmation flows, the V251 token journeys, the registration and
 * profile surfaces, the token revocation). The UserService-implements-
 * IdentitySpi precedent, composed for the six owning services this
 * packet's roots ride.
 */
@Component
public class AccountLifecycleSpiAdapter implements AccountLifecycleSpi {

    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final AccountEmailChangeService emailChangeService;
    private final AccountSelfServiceTokenService selfServiceTokenService;
    private final UserService userService;
    private final TokenRevocationService tokenRevocationService;
    private final AuthActionTokenService authActionTokenService;

    public AccountLifecycleSpiAdapter(PasswordResetService passwordResetService,
                                      EmailVerificationService emailVerificationService,
                                      AccountEmailChangeService emailChangeService,
                                      AccountSelfServiceTokenService selfServiceTokenService,
                                      UserService userService,
                                      TokenRevocationService tokenRevocationService,
                                      AuthActionTokenService authActionTokenService) {
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
        this.emailChangeService = emailChangeService;
        this.selfServiceTokenService = selfServiceTokenService;
        this.userService = userService;
        this.tokenRevocationService = tokenRevocationService;
        this.authActionTokenService = authActionTokenService;
    }

    @Override
    public void requestPasswordReset(String email) {
        passwordResetService.requestReset(email);
    }

    @Override
    public void resendEmailVerification(String email) {
        emailVerificationService.resend(email);
    }

    @Override
    public void confirmAccount(String email, String token) {
        requireTokenOwnedBy(email, token, AuthActionTokenPurpose.EMAIL_VERIFICATION);
        emailVerificationService.completeVerification(token);
    }

    @Override
    public void completePasswordReset(String email, String token, String newPassword) {
        requireTokenOwnedBy(email, token, AuthActionTokenPurpose.PASSWORD_RESET);
        passwordResetService.completeReset(token, newPassword);
    }

    /**
     * The 2026-11-05 #810 round review (Minor · Security & Privacy): the
     * request identifies an ACCOUNT and redeems a RIGHT — the right must
     * belong to the identified account. A token minted for account B used
     * to pass untouched while the request named account A (the email
     * argument was silently ignored): the redemption still hit B, and the
     * caller read success for a request that named A. The peek is
     * non-consuming ({@code accountOf}); an unknown or spent token falls
     * through to the owning service's own honest error, a KNOWN token of
     * the wrong owner answers the classified bad-request refusal HERE
     * (and the owning service's consume never runs).
     */
    private void requireTokenOwnedBy(String email, String token,
                                     AuthActionTokenPurpose purpose) {
        if (email == null || token == null) {
            return;
        }
        // The 2026-11-06 session-9 #806 final review (Minor · Functional
        // Correctness): the ownership comparison is case-INSENSITIVE on
        // both sides — the REST registration path stores the subject
        // verbatim (AuthController.register trims but never lowercases,
        // so mixed-case subjects exist) while the GraphQL accountRegister
        // normalizes to lowercase, and a case-sensitive equals falsely
        // rejected the CORRECT owner whenever the two sides normalized
        // differently (the valid link answered "not valid for the
        // identified account").
        java.util.function.Predicate<String> sameAccount =
                owner -> owner.trim().equalsIgnoreCase(email.trim());
        authActionTokenService.accountOf(token, purpose)
                .filter(sameAccount.negate())
                .ifPresent(owner -> {
                    throw new com.marketplace.shared.api.BadRequestException(
                            "This link is not valid for the identified account");
                });
    }

    @Override
    public void changePassword(Authentication caller, String oldPassword, String newPassword) {
        selfServiceTokenService.changePassword(caller, oldPassword, newPassword);
    }

    @Override
    public void requestEmailChange(Authentication caller, String newEmail, String password) {
        emailChangeService.requestChange(caller, newEmail, password);
    }

    @Override
    public String confirmEmailChange(String token) {
        return emailChangeService.confirmChange(token);
    }

    @Override
    public UUID register(String email, String password, String displayName) {
        return userService.register(email, password, displayName).getId();
    }

    @Override
    public void updateOwnProfile(Authentication caller, String displayName) {
        // Agent 1 (the CodeRabbit correctness round): the TRANSACTIONAL
        // write, not the cached/detached read. The previous one-liner
        // called getBySubject (@Cacheable + read-only), whose returned
        // copy absorbed updateProfile invisibly — accountUpdate answered
        // success while the display name never changed.
        userService.updateOwnProfileBySubject(caller.getName(), displayName);
    }

    @Override
    public void requestAccountDeletion(Authentication caller) {
        selfServiceTokenService.requestDeletion(caller);
    }

    @Override
    public void deleteAccountByToken(Authentication caller, String token) {
        selfServiceTokenService.deleteByToken(caller, token);
    }

    @Override
    public int deactivateAllTokens(Authentication caller) {
        return tokenRevocationService.deactivateAllFor(caller);
    }
}
