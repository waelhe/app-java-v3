package com.marketplace.shared.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Parses only the native passkey extension grant. Other grant types are left to
 * Spring Authorization Server's own converters.
 */
@Component
public final class NativePasskeyGrantAuthenticationConverter implements AuthenticationConverter {

    private static final String CHALLENGE_ID = "challenge_id";
    private static final String CREDENTIAL = "credential";
    private static final int MAX_CREDENTIAL_JSON_CHARS = 32_768;

    @Override
    public Authentication convert(HttpServletRequest request) {
        String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
        if (!NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue().equals(grantType)) {
            return null;
        }

        String challengeId = singleParameter(request, CHALLENGE_ID);
        String credentialJson = singleParameter(request, CREDENTIAL);
        if (!StringUtils.hasText(challengeId)
                || !StringUtils.hasText(credentialJson)
                || challengeId.length() > 64
                || credentialJson.length() > MAX_CREDENTIAL_JSON_CHARS) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();
        return new NativePasskeyGrantAuthenticationToken(clientPrincipal, challengeId, credentialJson);
    }

    private static String singleParameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return values[0];
    }
}
