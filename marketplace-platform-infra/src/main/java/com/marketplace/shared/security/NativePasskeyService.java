package com.marketplace.shared.security;

import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationProvider;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native passkey ceremonies using Spring Security's WebAuthn APIs.
 *
 * <p>The official relying-party operations, authentication provider and JDBC
 * credential repositories perform WebAuthn verification and credential
 * persistence. PostgreSQL stores only the request options needed to validate
 * each mobile ceremony; challenges expire after five minutes and are consumed
 * atomically once.</p>
 *
 * <p>The endpoints fail closed until the actual relying-party ID, allowed
 * Android origin, and matching Digital Asset Links signing-certificate
 * fingerprint are configured.</p>
 */
@Service
public final class NativePasskeyService {

    private static final String AUTHENTICATE = "AUTHENTICATE";
    private static final String REGISTER = "REGISTER";
    private static final TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>> ASSERTION_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<PublicKeyCredential<AuthenticatorAttestationResponse>> ATTESTATION_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final WebAuthnRelyingPartyOperations relyingPartyOperations;
    private final WebAuthnAuthenticationProvider webAuthnAuthenticationProvider;
    private final UserDetailsService userDetailsService;
    private final NativePasskeyProperties properties;
    private final JsonMapper webAuthnMapper;
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    public NativePasskeyService(
            JdbcTemplate jdbcTemplate,
            WebAuthnRelyingPartyOperations relyingPartyOperations,
            UserDetailsService userDetailsService,
            NativePasskeyProperties properties,
            JsonMapper webAuthnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.relyingPartyOperations = relyingPartyOperations;
        this.userDetailsService = userDetailsService;
        this.properties = properties;
        this.webAuthnMapper = webAuthnMapper;
        this.webAuthnAuthenticationProvider =
                new WebAuthnAuthenticationProvider(relyingPartyOperations, userDetailsService);
    }

    public NativePasskeyChallengeResponse createAuthenticationOptions() {
        requireConfigured();
        try {
            PublicKeyCredentialRequestOptions options =
                    this.relyingPartyOperations.createCredentialRequestOptions(
                            new ImmutablePublicKeyCredentialRequestOptionsRequest(null));
            return issueChallenge(AUTHENTICATE, null, options);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw serviceUnavailable("Passkey authentication is temporarily unavailable.", exception);
        }
    }

    public NativePasskeyChallengeResponse createRegistrationOptions(Authentication authentication) {
        requireConfigured();
        requireAuthenticated(authentication);
        requireEnabledUser(authentication.getName());

        try {
            PublicKeyCredentialCreationOptions options =
                    this.relyingPartyOperations.createPublicKeyCredentialCreationOptions(
                            new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication));
            return issueChallenge(REGISTER, authentication.getName(), options);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw serviceUnavailable("Passkey registration is temporarily unavailable.", exception);
        }
    }

    public void completeRegistration(
            String challengeId, String credentialJson, String label, Authentication authentication) {
        requireConfigured();
        requireAuthenticated(authentication);
        requireEnabledUser(authentication.getName());

        final String optionsJson;
        try {
            optionsJson = consumeChallenge(challengeId, REGISTER, authentication.getName());
        } catch (OAuth2AuthenticationException exception) {
            // The REST registration path speaks problem-detail semantics, not the
            // OAuth token-endpoint error channel its sibling grant uses: a malformed,
            // expired, replayed, or principal-mismatched challenge is an invalid
            // request payload (RFC 9110 section 15.5.1), not a failed authentication —
            // the caller is already authenticated and simply needs a fresh ceremony.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The passkey registration challenge is invalid or expired.", exception);
        } catch (DataAccessException exception) {
            throw serviceUnavailable("Passkey registration is temporarily unavailable.", exception);
        }
        try {
            PublicKeyCredentialCreationOptions options =
                    this.webAuthnMapper.readValue(optionsJson, PublicKeyCredentialCreationOptions.class);
            PublicKeyCredential<AuthenticatorAttestationResponse> credential =
                    this.webAuthnMapper.readValue(credentialJson, ATTESTATION_TYPE);
            String safeLabel = StringUtils.hasText(label) ? label.trim() : "Android device";
            if (safeLabel.length() > 1000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passkey label is too long.");
            }
            this.relyingPartyOperations.registerCredential(
                    new ImmutableRelyingPartyRegistrationRequest(
                            options, new RelyingPartyPublicKey(credential, safeLabel)));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (org.springframework.dao.DataAccessException exception) {
            // A repository outage is a server failure, not an invalid client assertion.
            // Keep the REST error contract distinct from WebAuthn verification failures.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Passkey registration is temporarily unavailable.", exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The passkey registration response could not be verified.", exception);
        }
    }

    /**
     * Consumes the server-issued assertion challenge and verifies the assertion
     * with Spring Security's official WebAuthn AuthenticationProvider.
     */
    public Authentication authenticate(String challengeId, String credentialJson) {
        if (!this.properties.isConfigured()) {
            // OAuth token-endpoint failures must use OAuth errors, not let a web
            // ResponseStatusException escape the framework's authentication filter.
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
        try {
            String optionsJson = consumeChallenge(challengeId, AUTHENTICATE, null);
            PublicKeyCredentialRequestOptions options =
                    this.webAuthnMapper.readValue(optionsJson, PublicKeyCredentialRequestOptions.class);
            PublicKeyCredential<AuthenticatorAssertionResponse> credential =
                    this.webAuthnMapper.readValue(credentialJson, ASSERTION_TYPE);
            Authentication result = this.webAuthnAuthenticationProvider.authenticate(
                    new WebAuthnAuthenticationRequestToken(
                            new RelyingPartyAuthenticationRequest(options, credential)));
            if (result == null || !result.isAuthenticated() || !StringUtils.hasText(result.getName())) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            }
            requireEnabledUserForGrant(result.getName());
            return result;
        } catch (OAuth2AuthenticationException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw temporaryOAuthServiceError(exception);
        } catch (Exception exception) {
            // Unknown, expired, replayed and invalid assertions all return the same grant error.
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
    }

    private NativePasskeyChallengeResponse issueChallenge(String purpose, String principalName, Object options) {
        try {
            // Opportunistic expiry cleanup; challenge consumption below is atomic.
            this.jdbcTemplate.update(
                    "DELETE FROM native_passkey_challenges WHERE expires_at <= CURRENT_TIMESTAMP");
            String optionsJson = this.webAuthnMapper.writeValueAsString(options);
            UUID challengeId = UUID.randomUUID();
            this.jdbcTemplate.update("""
                    INSERT INTO native_passkey_challenges
                        (challenge_id, purpose, principal_name, options_json, expires_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                    """, challengeId, purpose, principalName, optionsJson);
            return new NativePasskeyChallengeResponse(challengeId.toString(), optionsJson);
        } catch (Exception exception) {
            if (exception instanceof ResponseStatusException statusException) {
                throw statusException;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Passkey authentication is temporarily unavailable.", exception);
        }
    }

    private String consumeChallenge(String rawId, String purpose, String principalName) {
        final UUID challengeId;
        try {
            challengeId = UUID.fromString(rawId);
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }

        String sql = """
                DELETE FROM native_passkey_challenges
                WHERE challenge_id = ? AND purpose = ? AND expires_at > CURRENT_TIMESTAMP
                """ + (principalName == null ? "" : " AND principal_name = ?")
                + " RETURNING options_json";
        try {
            if (principalName == null) {
                return this.jdbcTemplate.queryForObject(sql, String.class, challengeId, purpose);
            }
            return this.jdbcTemplate.queryForObject(sql, String.class,
                    challengeId, purpose, principalName);
        } catch (EmptyResultDataAccessException exception) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
    }

    private void requireAuthenticated(Authentication authentication) {
        if (!this.trustResolver.isAuthenticated(authentication)
                || !StringUtils.hasText(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before registering a passkey.");
        }
    }

    private void requireEnabledUser(String username) {
        final UserDetails account;
        try {
            account = this.userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The account is not eligible to register a passkey.", exception);
        } catch (DataAccessException exception) {
            throw serviceUnavailable("The account status could not be verified.", exception);
        }
        if (!account.isEnabled() || !account.isAccountNonLocked()
                || !account.isAccountNonExpired() || !account.isCredentialsNonExpired()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The account is not eligible to register a passkey.");
        }
    }

    private void requireEnabledUserForGrant(String username) {
        try {
            requireEnabledUser(username);
        } catch (ResponseStatusException exception) {
            if (HttpStatus.FORBIDDEN.equals(exception.getStatusCode())) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            }
            throw temporaryOAuthServiceError(exception);
        } catch (RuntimeException exception) {
            throw temporaryOAuthServiceError(exception);
        }
    }

    private static ResponseStatusException serviceUnavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    private static OAuth2AuthenticationException temporaryOAuthServiceError(Throwable cause) {
        return new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.SERVER_ERROR,
                "Passkey authentication is temporarily unavailable.",
                null), cause);
    }

    private void requireConfigured() {
        if (!this.properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Native passkeys are not enabled until the relying-party ID, allowed Android origin, "
                            + "and matching app-signing certificate fingerprint are configured.");
        }
    }
}
