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

    public AccountLifecycleSpiAdapter(PasswordResetService passwordResetService,
                                      EmailVerificationService emailVerificationService,
                                      AccountEmailChangeService emailChangeService,
                                      AccountSelfServiceTokenService selfServiceTokenService,
                                      UserService userService,
                                      TokenRevocationService tokenRevocationService) {
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
        this.emailChangeService = emailChangeService;
        this.selfServiceTokenService = selfServiceTokenService;
        this.userService = userService;
        this.tokenRevocationService = tokenRevocationService;
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
        emailVerificationService.completeVerification(token);
    }

    @Override
    public void completePasswordReset(String email, String token, String newPassword) {
        passwordResetService.completeReset(token, newPassword);
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
        userService.updateOwnDisplayName(caller.getName(), displayName);
    }

    @Override
    public void requestAccountDeletion(Authentication caller) {
        selfServiceTokenService.requestDeletion(caller);
    }

    @Override
    public void deleteAccountByToken(String token) {
        selfServiceTokenService.deleteByToken(token);
    }

    @Override
    public int deactivateAllTokens(Authentication caller) {
        return tokenRevocationService.deactivateAllFor(caller);
    }
}
