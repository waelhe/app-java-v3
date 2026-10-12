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

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
        String[] grantTypes = request.getParameterValues(OAuth2ParameterNames.GRANT_TYPE);
        if (grantTypes == null) {
            return null;
        }
        if (grantTypes.length != 1) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        if (!NativePasskeyGrantAuthenticationToken.GRANT_TYPE.getValue().equals(grantTypes[0])) {
            return null;
        }

        String challengeId = singleParameter(request, CHALLENGE_ID);
        String credentialJson = singleParameter(request, CREDENTIAL);
        Set<String> requestedScopes = requestedScopes(request);
        if (!StringUtils.hasText(challengeId)
                || !StringUtils.hasText(credentialJson)
                || challengeId.length() > 64
                || credentialJson.length() > MAX_CREDENTIAL_JSON_CHARS) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();
        return new NativePasskeyGrantAuthenticationToken(
                clientPrincipal, challengeId, credentialJson, requestedScopes);
    }

    private static Set<String> requestedScopes(HttpServletRequest request) {
        String[] values = request.getParameterValues(OAuth2ParameterNames.SCOPE);
        if (values == null) {
            return Set.of();
        }
        if (values.length != 1 || values[0].length() > 256) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        String value = values[0].trim();
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split("\\s+"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String singleParameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return values[0];
    }
}
