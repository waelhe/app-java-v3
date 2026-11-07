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

    /**
     * Saleor {@code requestPasswordReset(channel, email, redirectUrl)} —
     * enumeration-safe.
     *
     * <p>The shard review (Major · Stability & Availability, 2026-11-01
     * wave 2): the ANONYMOUS account roots leave the shared
     * graphqlAccountMutation budget — a flood here must never starve the
     * authenticated customer/staff admin mutations — and the email
     * senders carry their own per-CALLER budget besides (the guard).</p>
     */
    @DgsMutation(field = DgsConstants.MUTATION.RequestPasswordReset)
    // The wave-4 #806 review (Minor · Security & Privacy): the reset request
    // carries the victim's email, so the caller-keyed budget alone lets an
    // attacker distribute requests across many addresses and flood ONE
    // mailbox. perAccount adds the second, email-hash-keyed permit — the
    // credential-stuffing defense tokenCreate already carries — so a single
    // account address carries its OWN bounded budget no matter how many
    // caller addresses the flood rotates through.
    @PerCallerBudget(surface = "graphqlAccountAnonymousMutation", perAccount = true)
    @RateLimiter(name = "graphqlAccountAnonymousMutation")
    public RequestPasswordReset requestPasswordReset(@InputArgument String channel,
                                                     @InputArgument String email,
                                                     @InputArgument String redirectUrl,
                                                     graphql.schema.DataFetchingEnvironment environment) {
        rejectChannel(channel);
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("An email is required");
        }
        // The 2026-11-05 #806 round-3 review (Minor · Functional
        // Correctness): registration lowercases the address before the
        // account is stored (accountRegister's own normalization), so a
        // reset request typed in uppercase must resolve the SAME stored
        // row — trim alone made the lookup miss, no email was sent, and
        // the response still read as success. The identical
        // trim+lowercase(Locale.ROOT) pair registration carries.
        accountLifecycleSpi.requestPasswordReset(
                email.trim().toLowerCase(java.util.Locale.ROOT));
        return RequestPasswordReset.newBuilder()
                .accountErrors(mappers.emptyAccountErrors())
                .errors(mappers.emptyAccountErrors()).build();
    }

    /**
     * Saleor {@code sendConfirmationEmail(channel, redirectUrl)} — the
     * authenticated caller's own verification resend. The pinned Saleor
     * 3.23.45 contract: {@code SendConfirmationEmail.Meta.permissions =
     * (AuthorizationFilters.AUTHENTICATED_USER,)} operating on
     * {@code info.context.user} — the root emails the CALLER's own
     * account, so it rides the authenticated graphqlAccountMutation
     * budget like its siblings passwordChange/accountRequestDeletion
     * (the anonymous family serves only roots an anonymous caller can
     * actually redeem; the per-account resend throttle is the identity
     * owner's own 60-second re-issue floor).
     */
    @DgsMutation(field = DgsConstants.MUTATION.SendConfirmationEmail)
    @RateLimiter(name = "graphqlAccountMutation")
    public SendConfirmationEmail sendConfirmationEmail(@InputArgument String channel,
                                                       @InputArgument String redirectUrl,
                                                       graphql.schema.DataFetchingEnvironment environment) {
        rejectChannel(channel);
        // The verification flow's own key: the address of the account whose
        // pending hold the caller re-arms — on this platform the login
        // username IS the email, so the caller's own subject carries it.
        accountLifecycleSpi.resendEmailVerification(currentAuthentication().getName());
        return SendConfirmationEmail.newBuilder()
                .accountErrors(mappers.emptyAccountErrors())
                .errors(mappers.emptyAccountErrors()).build();
    }

    /**
     * Saleor {@code confirmAccount(email, password, token)} — token-proofed.
     * The wave-3 review (Major · Stability): the per-caller budget this
     * root's wave-2 split intended — without it, one anonymous client's
     * setPassword spam exhausts the SHARED anonymous budget and starves
     * registration and password reset for every other caller (and the
     * token-guessing surface gets its own per-address budget besides).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ConfirmAccount)
    @PerCallerBudget(surface = "graphqlAccountAnonymousMutation")
    @RateLimiter(name = "graphqlAccountAnonymousMutation")
    public ConfirmAccount confirmAccount(@InputArgument String email,
                                         @InputArgument String password,
                                         @InputArgument String token,
                                         graphql.schema.DataFetchingEnvironment environment) {
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
        // The 2026-11-06 #806 review (Minor · Functional Correctness): the
        // redemption keys on the SAME normalized address the token was
        // minted for — registration lowercases before storing, so a
        // mixed-case confirmAccount email must reach the owner's
        // token-ownership comparison (requireTokenOwnedBy's
        // owner.equals(email)) already normalized, exactly like
        // requestPasswordReset above — trim alone made a valid token's
        // redemption answer the wrong-owner refusal.
        accountLifecycleSpi.confirmAccount(
                email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT), token);
        return ConfirmAccount.newBuilder()
                .user(null)
                .accountErrors(mappers.emptyAccountErrors())
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /**
     * Saleor {@code setPassword(email, password, token)} — the reset
     * redemption. The wave-3 review: the same per-caller budget
     * confirmAccount carries.
     */
    @DgsMutation(field = DgsConstants.MUTATION.SetPassword)
    @PerCallerBudget(surface = "graphqlAccountAnonymousMutation")
    @RateLimiter(name = "graphqlAccountAnonymousMutation")
    public SetPassword setPassword(@InputArgument String email,
                                   @InputArgument String password,
                                   @InputArgument String token,
                                   graphql.schema.DataFetchingEnvironment environment) {
        if (token == null || token.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("A token and a new password are required");
        }
        // The 2026-11-06 #806 review (Minor · Functional Correctness): the
        // same trim+lowercase(Locale.ROOT) pair confirmAccount carries —
        // the reset redemption must resolve the SAME stored row the reset
        // request (already normalized above) minted the token for.
        accountLifecycleSpi.completePasswordReset(
                email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT), token,
                password);
        return SetPassword.newBuilder()
                .user(null)
                .accountErrors(mappers.emptyAccountErrors())
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
                .accountErrors(mappers.emptyAccountErrors())
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code confirmEmailChange(channel, token)}. */
    @DgsMutation(field = DgsConstants.MUTATION.ConfirmEmailChange)
    // The 2026-11-06 #806 round review (Major · Security & Privacy): the
    // pinned Saleor 3.23.45 contract (AuthorizationFilters.AUTHENTICATED_
    // USER on perform_mutation — the user resolved from
    // info.context.user, any token whose user_id claim mismatches the
    // authenticated caller is rejected) binds the redemption to the
    // CALLER's identity, never the token alone. The root left the
    // anonymous surface (the PreLoginGraphQlGate allowlist) and now rides
    // the authenticated graphqlAccountMutation family its request leg
    // already carries; the owning service verifies the caller IS the
    // right's owner BEFORE the consumption (a mismatched caller answers
    // the classified denial and the legitimate owner's right survives).
    @RateLimiter(name = "graphqlAccountMutation")
    public ConfirmEmailChange confirmEmailChange(@InputArgument String channel,
                                                 @InputArgument String token,
                                                 graphql.schema.DataFetchingEnvironment environment) {
        rejectChannel(channel);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A token is required");
        }
        accountLifecycleSpi.confirmEmailChange(currentAuthentication(), token);
        return ConfirmEmailChange.newBuilder()
                .user(null)
                .accountErrors(mappers.emptyAccountErrors())
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /**
     * Saleor {@code accountRegister(input)} — the standing registration
     * flow. The wave-2 review's per-caller set: the registration root is
     * the second anonymous flood surface (after the grant path).
     */
    @DgsMutation(field = DgsConstants.MUTATION.AccountRegister)
    @PerCallerBudget(surface = "graphqlAccountAnonymousMutation")
    @RateLimiter(name = "graphqlAccountAnonymousMutation")
    public AccountRegister accountRegister(@InputArgument AccountRegisterInput input,
                                           graphql.schema.DataFetchingEnvironment environment) {
        // The 2026-11-05 #806 round-3 review (Minor · Functional
        // Correctness): the input's channel field declares the same
        // single-channel contract every sibling root rejects — silently
        // accepting it here let a client believe a channel-scoped
        // registration happened.
        if (input != null) {
            rejectChannel(input.getChannel());
        }
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
        // Row 16 (agent findings 2026-10-26 14:33 export, CWE-1333): the
        // length gate FIRST — the shape pattern is evaluated only for an
        // input already inside the login store's domain
        // (auth_users.username VARCHAR(50), V13). The pre-fix order ran
        // the ambiguous backtracking pattern on the raw input before the
        // length cap, so a malformed domain with many dot separators made
        // the matcher rescan suffixes quadratically past the cap — the
        // bounded input answers in bounded time.
        if (email.length() > 50 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException(
                    "A valid email address of at most 50 characters is required");
        }
        String password = input.getPassword();
        // The shard review (CodeRabbit 2026-11-01, Minor · Security): bcrypt's
        // documented input ceiling is 72 BYTES of UTF-8 — never 72 UTF-16
        // characters. A 40-character Arabic password is ~80 UTF-8 bytes: it
        // passed the old character count while silently exceeding the bcrypt
        // limit. The byte-encoded length is the real ceiling (rejected
        // here, never silently truncated by the encoder).
        int passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (password.length() < 8 || passwordBytes > 72) {
            throw new IllegalArgumentException(
                    "The password must be at least 8 characters and at most 72 bytes"
                            + " (the bcrypt byte ceiling)");
        }
        accountLifecycleSpi.register(email, password,
                composeDisplayName(input.getFirstName(), input.getLastName()));
        return AccountRegister.newBuilder()
                .user(null)
                .accountErrors(mappers.emptyAccountErrors())
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
        // The 2026-11-04 #806 round (Minor · Functional Correctness): the
        // SDL declares defaultBillingAddress/defaultShippingAddress, but
        // the identity owner's own-profile write carries no address
        // fields — a supplied value was a SILENT no-op (the never-a-
        // silent-no-op rule, the UserManagementDataFetcher's own
        // rejection vocabulary for the same fields).
        if (input.getDefaultBillingAddress() != null || input.getDefaultShippingAddress() != null) {
            throw new IllegalArgumentException(
                    "accountUpdate defaultBillingAddress/defaultShippingAddress are not implemented "
                            + "on this platform yet");
        }
        // The 2026-11-04 #806 round (Major · Functional Correctness): the
        // identity store carries ONE composed display name — Saleor's
        // firstName/lastName are separate columns there, so a partial
        // Saleor-shaped update (firstName alone) would compose from the
        // input's single half and REPLACE the whole stored name, silently
        // dropping the other half. The split-back is impossible (a
        // composed string cannot be faithfully decomposed), so the honest
        // contract is both-or-neither: the classified validation error —
        // the same taxonomy the impersonation argument above answers.
        if ((input.getFirstName() != null) != (input.getLastName() != null)) {
            throw new IllegalArgumentException(
                    "accountUpdate requires firstName and lastName together on this platform "
                            + "(the identity store carries one composed display name — a partial "
                            + "name update would drop the stored half)");
        }
        accountLifecycleSpi.updateOwnProfile(currentAuthentication(),
                composeDisplayName(input.getFirstName(), input.getLastName()));
        return AccountUpdate.newBuilder()
                .user(currentUserOrNull())
                .accountErrors(mappers.emptyAccountErrors())
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
                .accountErrors(mappers.emptyAccountErrors())
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code accountDelete(token)} — the redemption through the standing erasure. */
    @DgsMutation(field = DgsConstants.MUTATION.AccountDelete)
    // The 12:44:42Z #806 review (Minor · Security & Privacy): the pinned
    // Saleor 3.23.45 contract requires AUTHENTICATED_USER on AccountDelete
    // and validates the one-time token against the authenticated caller
    // (try_generators(user=info.context.user, token=token)) — the root is
    // REMOVED from the anonymous pre-login family and the redemption is
    // caller-bound. The wave-4 anonymous-budget annotations are gone with
    // it: the root rides the authenticated graphqlAccountMutation budget,
    // the same shape accountRequestDeletion carries.
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountDelete accountDelete(@InputArgument String token,
                                       graphql.schema.DataFetchingEnvironment environment) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A deletion token is required");
        }
        accountLifecycleSpi.deleteAccountByToken(currentAuthentication(), token);
        return AccountDelete.newBuilder()
                .accountErrors(mappers.emptyAccountErrors())
                .errors(mappers.emptyAccountErrors()).build();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private PasswordChange passwordChangePayload() {
        return PasswordChange.newBuilder()
                .user(currentUserOrNull())
                .accountErrors(mappers.emptyAccountErrors())
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
        // The wave-3 review (Minor · Security): AnonymousAuthenticationToken
        // answers isAuthenticated() == true — the type check is the only
        // honest wall (the guard is defense-in-depth: sendConfirmationEmail
        // is currently behind the PreLoginGraphQlGate, but the guard must
        // not silently accept an anonymous caller if the gate's allowlist
        // ever widens).
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new org.springframework.security.authentication
                    .AuthenticationCredentialsNotFoundException(
                    "Authentication is required for account operations");
        }
        return authentication;
    }
}
