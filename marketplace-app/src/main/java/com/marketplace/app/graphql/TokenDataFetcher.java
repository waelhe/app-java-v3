package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.CreateToken;
import com.marketplace.app.graphql.generated.types.DeactivateAllUserTokens;
import com.marketplace.app.graphql.generated.types.RefreshToken;
import com.marketplace.app.graphql.generated.types.VerifyToken;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * GraphQL adapter for the Saleor Authentication-domain mutations —
 * {@code tokenCreate} (the dashboard's password-grant sign-in over the
 * platform's own SAS primitives), {@code tokenRefresh} (the rotation),
 * {@code tokenVerify} (the resource server's own decoder) and
 * {@code tokensDeactivateAll} (the caller's sign-out-everywhere).
 *
 * <p>Transport-only adapter (the frozen §7 contract): argument validation
 * and the wire mapping; every rule lives in the owning use cases. The
 * password-grant surface is rate-limited from day one — the unconfigured
 * named instance falls back to the shared resilience4j {@code default}
 * budget (100/min, measured) with the tighter yml instance proposed for
 * A0's integration pass.</p>
 */
@DgsComponent
public class TokenDataFetcher {

    private final PlatformTokenGrantService tokenGrantService;
    private final AccountLifecycleSpi accountLifecycleSpi;
    private final com.marketplace.identity.spi.IdentitySpi identitySpi;
    private final AgentOneGraphQlMappers mappers;
    private final UserGraphQlMapper userMapper;

    public TokenDataFetcher(PlatformTokenGrantService tokenGrantService,
                            AccountLifecycleSpi accountLifecycleSpi,
                            com.marketplace.identity.spi.IdentitySpi identitySpi,
                            AgentOneGraphQlMappers mappers,
                            UserGraphQlMapper userMapper) {
        this.tokenGrantService = tokenGrantService;
        this.accountLifecycleSpi = accountLifecycleSpi;
        this.identitySpi = identitySpi;
        this.mappers = mappers;
        this.userMapper = userMapper;
    }

    /** Saleor {@code tokenCreate(audience, email, password)} — the password grant. */
    @DgsMutation(field = DgsConstants.MUTATION.TokenCreate)
    @RateLimiter(name = "graphqlTokenMutation")
    public CreateToken tokenCreate(@InputArgument String audience,
                                   @InputArgument String email,
                                   @InputArgument String password) {
        if (audience != null && !audience.isBlank()) {
            // Saleor's custom-audience prefix is a JWT customization this
            // platform's single-audience contract does not carry — the
            // declared-but-unexecuted argument answers the classified
            // error (the frozen VAL-001 discipline), never a silent no-op.
            throw new IllegalArgumentException(
                    "tokenCreate audience is not implemented on this platform yet");
        }
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "An email and a password are required for the token grant");
        }
        PlatformTokenGrantService.IssuedTokens issued = tokenGrantService.grant(email, password);
        return CreateToken.newBuilder()
                .token(issued.accessToken())
                .refreshToken(issued.refreshToken())
                .csrfToken(issued.csrfToken())
                .user(issued.user() == null ? null : userMapper.toGraphQlUser(issued.user()))
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code tokenRefresh(csrfToken, refreshToken)} — the rotation. */
    @DgsMutation(field = DgsConstants.MUTATION.TokenRefresh)
    @RateLimiter(name = "graphqlTokenMutation")
    public RefreshToken tokenRefresh(@InputArgument String csrfToken,
                                     @InputArgument String refreshToken) {
        // The CSRF token is Saleor's cookie-mode refresh protection; the
        // platform returns the refresh token in the payload (no cookie
        // mode), so the argument stays declared-but-unexecuted — the
        // classified error, never a silent no-op.
        if (csrfToken != null && !csrfToken.isBlank()) {
            throw new IllegalArgumentException(
                    "tokenRefresh csrfToken is not implemented on this platform yet");
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("A refresh token is required");
        }
        PlatformTokenGrantService.IssuedTokens issued = tokenGrantService.refresh(refreshToken);
        return RefreshToken.newBuilder()
                .token(issued.accessToken())
                .user(issued.user() == null ? null : userMapper.toGraphQlUser(issued.user()))
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    /** Saleor {@code tokenVerify(token)} — the resource server's own decoder. */
    @DgsMutation(field = DgsConstants.MUTATION.TokenVerify)
    @RateLimiter(name = "graphqlTokenMutation")
    public VerifyToken tokenVerify(@InputArgument String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A token is required");
        }
        PlatformTokenGrantService.VerifiedToken verified = tokenGrantService.verify(token);
        VerifyToken.Builder builder = VerifyToken.newBuilder()
                .isValid(verified.valid())
                .payload(verified.claims().isEmpty() ? null : verified.claims().toString())
                .errors(mappers.emptyAccountErrors());
        if (verified.valid() && verified.subject() != null) {
            identitySpi.findCurrentUserAccount(
                            new org.springframework.security.authentication
                                    .UsernamePasswordAuthenticationToken(verified.subject(), null, java.util.List.of()))
                    .ifPresent(account -> builder.user(userMapper.toGraphQlUser(account)));
        }
        return builder.build();
    }

    /** Saleor {@code tokensDeactivateAll} — the caller's sign-out-everywhere. */
    @DgsMutation(field = DgsConstants.MUTATION.TokensDeactivateAll)
    @RateLimiter(name = "graphqlTokenMutation")
    public DeactivateAllUserTokens tokensDeactivateAll() {
        Authentication caller = currentAuthentication();
        accountLifecycleSpi.deactivateAllTokens(caller);
        return DeactivateAllUserTokens.newBuilder()
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication
                    .AuthenticationCredentialsNotFoundException(
                    "Authentication is required for token administration");
        }
        return authentication;
    }
}
