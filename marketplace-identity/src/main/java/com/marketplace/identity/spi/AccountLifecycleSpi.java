package com.marketplace.identity.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * additive account-lifecycle seam of the {@code identity-spi} named
 * interface — the self-service legs the Saleor account/password mutations
 * ride: the standing reset/confirmation flows
 * ({@code requestPasswordReset}/{@code sendConfirmationEmail}/
 * {@code confirmAccount}/{@code setPassword}), the profile update
 * ({@code accountRegister}/{@code accountUpdate}), the token-based
 * self-deletion and email-change journeys (V251 purposes), the
 * authenticated password change, and {@code tokensDeactivateAll}.
 */
@NamedInterface("identity-spi")
public interface AccountLifecycleSpi {

    /** {@code requestPasswordReset}: the enumeration-safe request leg. */
    void requestPasswordReset(String email);

    /** {@code sendConfirmationEmail}: the verification resend leg. */
    void resendEmailVerification(String email);

    /** {@code confirmAccount}: the verification redemption leg. */
    void confirmAccount(String email, String token);

    /** {@code setPassword}: the reset redemption leg (the new secret's own surface). */
    void completePasswordReset(String email, String token, String newPassword);

    /** {@code passwordChange}: the authenticated secret rotation. */
    void changePassword(Authentication caller, String oldPassword, String newPassword);

    /** {@code requestEmailChange}: the password-verified request leg. */
    void requestEmailChange(Authentication caller, String newEmail, String password);

    /** {@code confirmEmailChange}: the redemption leg — answers the new email. */
    String confirmEmailChange(String token);

    /** {@code accountRegister}: the standing registration flow — answers the created id. */
    UUID register(String email, String password, String displayName);

    /** {@code accountUpdate}: the caller's own profile update (displayName composition). */
    void updateOwnProfile(Authentication caller, String displayName);

    /** {@code accountRequestDeletion}: the one-time erasure right. */
    void requestAccountDeletion(Authentication caller);

    /** {@code accountDelete}: the token redemption through the standing erasure. */
    void deleteAccountByToken(String token);

    /** {@code tokensDeactivateAll}: kills the caller's own issued authorizations. */
    int deactivateAllTokens(Authentication caller);
}
