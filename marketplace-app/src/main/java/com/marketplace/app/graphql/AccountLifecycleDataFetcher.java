package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.AccountDelete;
import com.marketplace.app.graphql.generated.types.AccountRegister;
import com.marketplace.app.graphql.generated.types.AccountRegisterInput;
import com.marketplace.app.graphql.generated.types.AccountRequestDeletion;
import com.marketplace.app.graphql.generated.types.AccountUpdate;
import com.marketplace.app.graphql.generated.types.AccountInput;
import com.marketplace.app.graphql.generated.types.ConfirmAccount;
import com.marketplace.app.graphql.generated.types.ConfirmEmailChange;
import com.marketplace.app.graphql.generated.types.PasswordChange;
import com.marketplace.app.graphql.generated.types.RequestEmailChange;
import com.marketplace.app.graphql.generated.types.RequestPasswordReset;
import com.marketplace.app.graphql.generated.types.SendConfirmationEmail;
import com.marketplace.app.graphql.generated.types.SetPassword;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * GraphQL adapter for the Saleor account/password lifecycle mutations —
 * the standing reset/confirmation flows, the registration, the profile
 * update, the V251 token journeys (email change, self deletion) and the
 * authenticated password change. Transport-only (the frozen §7
 * contract): the enumeration-safe flows, TTLs and two-store moves all
 * live in the identity owning use cases.
 */
@DgsComponent
public class AccountLifecycleDataFetcher {

    private final AccountLifecycleSpi accountLifecycleSpi;
    private final IdentitySpi identitySpi;
    private final AgentOneGraphQlMappers mappers;
    private final UserGraphQlMapper userMapper;

    public AccountLifecycleDataFetcher(AccountLifecycleSpi accountLifecycleSpi,
                                       IdentitySpi identitySpi,
                                       AgentOneGraphQlMappers mappers,
                                       UserGraphQlMapper userMapper) {
        this.accountLifecycleSpi = accountLifecycleSpi;
        this.identitySpi = identitySpi;
        this.mappers = mappers;
        this.userMapper = userMapper;
    }

    /** Saleor {@code requestPasswordReset(channel, email, redirectUrl)} — enumeration-safe. */
    @DgsMutation(field = DgsConstants.MUTATION.RequestPasswordReset)
    @RateLimiter(name = "graphqlAccountMutation")
    public RequestPasswordReset requestPasswordReset(@InputArgument String channel,
                                                     @InputArgument String email,
                                                     @InputArgument String redirectUrl) {
        rejectChannel(channel);
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("An email is required");
        }
        accountLifecycleSpi.requestPasswordReset(email.trim());
        return RequestPasswordReset.newBuilder().errors(mappers.emptyAccountErrors()).build();
    }

    /** Saleor {@code sendConfirmationEmail(channel, redirectUrl)}. */
    @DgsMutation(field = DgsConstants.MUTATION.SendConfirmationEmail)
    @RateLimiter(name = "graphqlAccountMutation")
    public SendConfirmationEmail sendConfirmationEmail(@InputArgument String channel,
                                                       @InputArgument String redirectUrl) {
        rejectChannel(channel);
        // The verification flow's own key: the address of the account whose
        // pending hold the caller re-arms — on this platform the login
        // username IS the email, so the caller's own subject carries it.
        accountLifecycleSpi.resendEmailVerification(currentAuthentication().getName());
        return SendConfirmationEmail.newBuilder().errors(mappers.emptyAccountErrors()).build();
    }

    /** Saleor {@code confirmAccount(email, password, token)}. */
    @DgsMutation(field = DgsConstants.MUTATION.ConfirmAccount)
    @RateLimiter(name = "graphqlAccountMutation")
    public ConfirmAccount confirmAccount(@InputArgument String email,
                                         @InputArgument String password,
                                         @InputArgument String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A confirmation token is required");
        }
        if (password != null && !password.isBlank()) {
            // The merge-on-confirm mode is a Saleor account-merging
            // capability this platform's single-store identity does not
            // carry — declared-but-unexecuted, the classified error.
            throw new IllegalArgumentException(
                    "confirmAccount password (the merge mode) is not implemented on this platform yet");
        }
        accountLifecycleSpi.confirmAccount(email == null ? null : email.trim(), token);
        return ConfirmAccount.newBuilder()
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code setPassword(email, password, token)} — the reset redemption. */
    @DgsMutation(field = DgsConstants.MUTATION.SetPassword)
    @RateLimiter(name = "graphqlAccountMutation")
    public SetPassword setPassword(@InputArgument String email,
                                   @InputArgument String password,
                                   @InputArgument String token) {
        if (token == null || token.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("A token and a new password are required");
        }
        accountLifecycleSpi.completePasswordReset(email == null ? null : email.trim(), token,
                password);
        return SetPassword.newBuilder()
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code passwordChange(newPassword, oldPassword)} — the caller's own rotation. */
    @DgsMutation(field = DgsConstants.MUTATION.PasswordChange)
    @RateLimiter(name = "graphqlAccountMutation")
    public PasswordChange passwordChange(@InputArgument String newPassword,
                                         @InputArgument String oldPassword) {
        accountLifecycleSpi.changePassword(currentAuthentication(), oldPassword, newPassword);
        return passwordChangePayload();
    }

    /** Saleor {@code requestEmailChange(channel, newEmail, password, redirectUrl)}. */
    @DgsMutation(field = DgsConstants.MUTATION.RequestEmailChange)
    @RateLimiter(name = "graphqlAccountMutation")
    public RequestEmailChange requestEmailChange(@InputArgument String channel,
                                                 @InputArgument String newEmail,
                                                 @InputArgument String password,
                                                 @InputArgument String redirectUrl) {
        rejectChannel(channel);
        accountLifecycleSpi.requestEmailChange(currentAuthentication(), newEmail, password);
        return RequestEmailChange.newBuilder()
                .user(currentUserOrNull())
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code confirmEmailChange(channel, token)}. */
    @DgsMutation(field = DgsConstants.MUTATION.ConfirmEmailChange)
    @RateLimiter(name = "graphqlAccountMutation")
    public ConfirmEmailChange confirmEmailChange(@InputArgument String channel,
                                                 @InputArgument String token) {
        rejectChannel(channel);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A token is required");
        }
        accountLifecycleSpi.confirmEmailChange(token);
        return ConfirmEmailChange.newBuilder()
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code accountRegister(input)} — the standing registration flow. */
    @DgsMutation(field = DgsConstants.MUTATION.AccountRegister)
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountRegister accountRegister(@InputArgument AccountRegisterInput input) {
        if (input == null || input.getEmail() == null || input.getEmail().isBlank()
                || input.getPassword() == null || input.getPassword().isBlank()) {
            throw new IllegalArgumentException("An email and a password are required");
        }
        // m9 (the CodeRabbit security round): the REST registration DTO's
        // own request-layer contract, mirrored at THIS transport's boundary
        // — the email is a valid address capped at 50 characters (the
        // login store's own domain: auth_users.username VARCHAR(50), V13 —
        // a longer address answers the classified 400, never a
        // storage-time 500), the password is 8..72 (the bcrypt byte
        // ceiling — rejected, never silently truncated), and the address
        // lowercases as the case-insensitive account identifier (the
        // requireEmail discipline: one address owns exactly one account).
        String email = input.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 50) {
            throw new IllegalArgumentException(
                    "A valid email address of at most 50 characters is required");
        }
        String password = input.getPassword();
        if (password.length() < 8 || password.length() > 72) {
            throw new IllegalArgumentException("The password must be between 8 and 72 characters");
        }
        accountLifecycleSpi.register(email, password,
                composeDisplayName(input.getFirstName(), input.getLastName()));
        return AccountRegister.newBuilder()
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code accountUpdate(customerId, input)} — the caller's own profile. */
    @DgsMutation(field = DgsConstants.MUTATION.AccountUpdate)
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountUpdate accountUpdate(@InputArgument String customerId,
                                       @InputArgument AccountInput input) {
        if (customerId != null && !customerId.isBlank()) {
            // Saleor reserves the impersonation argument for
            // AUTHENTICATED_APP callers — the platform has no app grant.
            throw new IllegalArgumentException(
                    "accountUpdate customerId (app impersonation) is not implemented on this platform yet");
        }
        if (input == null) {
            throw new IllegalArgumentException("An input is required");
        }
        accountLifecycleSpi.updateOwnProfile(currentAuthentication(),
                composeDisplayName(input.getFirstName(), input.getLastName()));
        return AccountUpdate.newBuilder()
                .user(currentUserOrNull())
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code accountRequestDeletion(channel, redirectUrl)}. */
    @DgsMutation(field = DgsConstants.MUTATION.AccountRequestDeletion)
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountRequestDeletion accountRequestDeletion(@InputArgument String channel,
                                                         @InputArgument String redirectUrl) {
        rejectChannel(channel);
        accountLifecycleSpi.requestAccountDeletion(currentAuthentication());
        return AccountRequestDeletion.newBuilder()
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code accountDelete(token)} — the redemption through the standing erasure. */
    @DgsMutation(field = DgsConstants.MUTATION.AccountDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountDelete accountDelete(@InputArgument String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A deletion token is required");
        }
        accountLifecycleSpi.deleteAccountByToken(token);
        return AccountDelete.newBuilder().errors(mappers.emptyAccountErrors()).build();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private PasswordChange passwordChangePayload() {
        return PasswordChange.newBuilder()
                .user(currentUserOrNull())
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    private com.marketplace.app.graphql.generated.types.User currentUserOrNull() {
        return identitySpi.findCurrentUserAccount(currentAuthentication())
                .map(userMapper::toGraphQlUser)
                .orElse(null);
    }

    /** The channel argument: the platform is a single-channel store — declared, rejected. */
    private static void rejectChannel(String channel) {
        if (channel != null && !channel.isBlank()) {
            throw new IllegalArgumentException(
                    "The channel argument is not implemented on this platform yet "
                            + "(the platform is a single-channel store)");
        }
    }

    static String composeDisplayName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String composed = (first + " " + last).trim();
        return composed.isEmpty() ? null : composed;
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication
                    .AuthenticationCredentialsNotFoundException(
                    "Authentication is required for account operations");
        }
        return authentication;
    }
}
