package com.marketplace.shared.security;

import java.util.Map;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

/**
 * OAuth extension grant backed by a verified WebAuthn assertion.
 *
 * <p>This is an RFC 6749 extension grant registered only for the secret-less
 * Android client. The proof is an assertion signed by a registered passkey;
 * this token never carries a password or client secret.</p>
 */
public final class NativePasskeyGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {

    public static final AuthorizationGrantType GRANT_TYPE =
            new AuthorizationGrantType("urn:marketplace:params:oauth:grant-type:passkey");

    private final String challengeId;
    private final String credentialJson;
    private final Set<String> requestedScopes;

    public NativePasskeyGrantAuthenticationToken(
            Authentication clientPrincipal,
            String challengeId,
            String credentialJson,
            Set<String> requestedScopes) {
        super(GRANT_TYPE, clientPrincipal, Map.of());
        this.challengeId = challengeId;
        this.credentialJson = credentialJson;
        this.requestedScopes = Set.copyOf(requestedScopes);
    }

    public String getChallengeId() {
        return challengeId;
    }

    public String getCredentialJson() {
        return credentialJson;
    }

    public Set<String> getRequestedScopes() {
        return requestedScopes;
    }
}
