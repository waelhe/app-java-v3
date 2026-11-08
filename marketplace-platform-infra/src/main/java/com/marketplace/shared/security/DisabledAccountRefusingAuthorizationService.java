package com.marketplace.shared.security;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.JdbcUserDetailsManager;

/**
 * The 2026-11-08 #810 round-5 review (Major · Security & Privacy): the
 * account-state wall at the authorization STORE — Spring Authorization
 * Server's own code-flow surfaces ({@code /oauth2/authorize} and the code
 * exchange at {@code /oauth2/token}) persist through
 * {@link OAuth2AuthorizationService} directly, so the two protected paths
 * (the password grant's post-save re-verification and the refresh door's
 * {@code loginAccountEnabled} check in {@code PlatformTokenGrantService})
 * never saw them. A disable committing between the authorize request's
 * authentication and its {@code save()} landed a live authorization row
 * AFTER the disable's own {@code DELETE ... BY PRINCIPAL} had run — the
 * exchange then minted an access token plus a refresh token the refresh
 * doors would have kept refusing, but the grant was issued anyway.
 *
 * <p><b>The seam is the documented one:</b> {@code OAuth2AuthorizationService}
 * is Spring Authorization Server's storage SPI — decorating the JDBC
 * implementation at the bean definition adds the platform's own state law
 * without touching any endpoint or provider internals. The decorator
 * refuses to PERSIST an authorization whose principal is a DISABLED (or
 * absent) login row, throwing the SAME {@link DisabledException} the
 * password grant's raced-row refusal carries — the endpoints' exception
 * mappings stay the ones they already speak.</p>
 *
 * <p><b>The truth source:</b> the SAME {@link JdbcUserDetailsManager} bean
 * the authentication chain reads — {@code loginAccountEnabled}'s own
 * semantics, verbatim (an absent login row answers "nothing may be
 * granted for it").</p>
 *
 * <p><b>Scope guard:</b> {@code client_credentials} authorizations are
 * EXEMPT — their principal is the CLIENT id, not a user subject, and no
 * {@code auth_users} row exists for them by design. Every user-principal
 * grant ({@code authorization_code}, {@code refresh_token}, the platform's
 * {@code password}) IS checked, at every save — the authorize-side
 * initial insert AND the exchange-side token-bearing update.</p>
 *
 * <p><b>Residual window, documented:</b> the check-then-save pair is not
 * atomic with a concurrent disable committing between the two. The two
 *-layer design (the platform's own refresh-door precedent) bounds it: the
 * exchange's save re-checks the state a second time on the token-minting
 * leg, and every refresh path (the SAS refresh grant's save rides this
 * decorator too; the platform refresh door checks again) refuses the
 * disabled principal — the surviving exposure is one already-minted
 * access token within its standing 900s stateless-decode TTL, the same
 * bounded trade the rotation ledger documents.</p>
 */
final class DisabledAccountRefusingAuthorizationService implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;
    private final JdbcUserDetailsManager userDetailsManager;

    DisabledAccountRefusingAuthorizationService(OAuth2AuthorizationService delegate,
                                                JdbcUserDetailsManager userDetailsManager) {
        this.delegate = delegate;
        this.userDetailsManager = userDetailsManager;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        requireEnabledLoginAccount(authorization);
        delegate.save(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token,
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType tokenType) {
        return delegate.findByToken(token, tokenType);
    }

    private void requireEnabledLoginAccount(OAuth2Authorization authorization) {
        AuthorizationGrantType grantType = authorization.getAuthorizationGrantType();
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(grantType)) {
            return;
        }
        String principal = authorization.getPrincipalName();
        UserDetails login;
        try {
            login = userDetailsManager.loadUserByUsername(principal);
        } catch (UsernameNotFoundException absent) {
            // loginAccountEnabled's own contract: "A subject with no login
            // row answers false (nothing may be granted for it)."
            throw new DisabledException("User is disabled");
        }
        if (!login.isEnabled()) {
            throw new DisabledException("User is disabled");
        }
    }
}
