package com.marketplace.shared.security;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Supplies the registered public-client identity only for the native passkey
 * extension grant. Spring Security's built-in public-client converter correctly
 * requires a PKCE code_verifier for authorization_code; that flow is not used for
 * the passkey grant. This converter adds no user identity and no client secret.
 *
 * <p>The returned, unauthenticated token is accepted only by
 * {@link NativePasskeyClientAuthenticationProvider}, which resolves and validates
 * the exact configured {@code RegisteredClient} before the OAuth grant converter
 * can run.</p>
 */
@Component
public final class NativePasskeyClientAuthenticationConverter implements AuthenticationConverter {

    static final String CLIENT_AUTHENTICATION_MARKER = "marketplace_native_passkey_client_authentication";

    private final String tokenEndpoint;

    public NativePasskeyClientAuthenticationConverter(AuthorizationServerSettings settings) {
        this.tokenEndpoint = settings.getTokenEndpoint();
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || !this.tokenEndpoint.equals(request.getServletPath())) {
            return null;
        }

        String[] grantTypes = request.getParameterValues(OAuth2ParameterNames.GRANT_TYPE);
        if (grantTypes == null) {
            return null;
        }
        boolean passkeyGrantPresent = false;
        for (String grantType : grantTypes) {
            if (NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue().equals(grantType)) {
                passkeyGrantPresent = true;
                break;
            }
        }
        if (!passkeyGrantPresent) {
            return null;
        }
        if (grantTypes.length != 1
                || !NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue().equals(grantTypes[0])) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        // Client Authentication Method "none" does not carry a secret or HTTP auth.
        if (StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))
                || request.getParameterValues(OAuth2ParameterNames.CLIENT_SECRET) != null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }

        String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
        if (clientIds == null || clientIds.length != 1
                || !StringUtils.hasText(clientIds[0]) || clientIds[0].length() > 256) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        return new OAuth2ClientAuthenticationToken(
                clientIds[0],
                ClientAuthenticationMethod.NONE,
                null,
                Map.of(CLIENT_AUTHENTICATION_MARKER, Boolean.TRUE));
    }
}
