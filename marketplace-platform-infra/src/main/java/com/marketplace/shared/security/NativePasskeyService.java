package com.marketplace.shared.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationProvider;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Server-side WebAuthn ceremonies for native passkey sign-in and enrollment.
 *
 * <p>Credential verification, challenge validation, origin checks, RP ID checks,
 * signature-counter updates and credential persistence are delegated to Spring
 * Security's WebAuthn implementation. Ceremony challenges are stored in PostgreSQL,
 * expire after five minutes and are atomically consumed exactly once.</p>
 *
 * <p>The feature deliberately fails closed until the operator configures the RP ID
 * and exact Android signing-certificate origin. An invented or debug-key origin
 * must never become a production trust decision.</p>
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
    private final JsonMapper webAuthnMapper;
    private final String rpId;
    private final Set<String> allowedOrigins;

    public NativePasskeyService(
            JdbcTemplate jdbcTemplate,
            JdbcPublicKeyCredentialUserEntityRepository userEntities,
            JdbcUserCredentialRepository userCredentials,
            UserDetailsService userDetailsService,
            @Value("\${WEBAUTHN_RP_ID:localhost}") String rpId,
            @Value("\${WEBAUTHN_RP_NAME:Marketplace}") String rpName,
            @Value("\${WEBAUTHN_ALLOWED_ORIGINS:}") String allowedOrigins) {
        this.jdbcTemplate = jdbcTemplate;
        this.userDetailsService = userDetailsService;
        this.rpId = rpId == null ? "" : rpId.trim();
        this.allowedOrigins = parseSet(allowedOrigins);
        this.webAuthnMapper = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

        Webauthn4JRelyingPartyOperations operations = new Webauthn4JRelyingPartyOperations(
                userEntities,
                userCredentials,
                PublicKeyCredentialRpEntity.builder()
                        .id(this.rpId.isBlank() ? "localhost" : this.rpId)
                        .name(StringUtils.hasText(rpName) ? rpName.trim() : "Marketplace")
                        .build(),
                this.allowedOrigins);
        operations.setCustomizeRequestOptions(options ->
                options.userVerification(UserVerificationRequirement.REQUIRED));
        this.relyingPartyOperations = operations;
        this.webAuthnAuthenticationProvider = new WebAuthnAuthenticationProvider(operations, userDetailsService);
    }

    public NativePasskeyChallengeResponse createAuthenticationOptions() {
        requireConfigured();
        PublicKeyCredentialRequestOptions options =
                this.relyingPartyOperations.createCredentialRequestOptions(
                        new ImmutablePublicKeyCredentialRequestOptionsRequest(null));
        return issueChallenge(AUTHENTICATE, null, options);
    }

    public NativePasskeyChallengeResponse createRegistrationOptions(Authentication authentication) {
        requireConfigured();
        if (authentication == null || !authentication.isAuthenticated()
                || !StringUtils.hasText(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before registering a passkey.");
        }
        requireEnabledUser(authentication.getName());

        PublicKeyCredentialCreationOptions options =
                this.relyingPartyOperations.createPublicKeyCredentialCreationOptions(
                        new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication));
        return issueChallenge(REGISTER, authentication.getName(), options);
    }

    public void completeRegistration(
            String challengeId, String credentialJson, String label, Authentication authentication) {
        requireConfigured();
        if (authentication == null || !authentication.isAuthenticated()
                || !StringUtils.hasText(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in before registering a passkey.");
        }
        requireEnabledUser(authentication.getName());
        String optionsJson = consumeChallenge(challengeId, REGISTER, authentication.getName());
        try {
            PublicKeyCredentialCreationOptions options =
                    this.webAuthnMapper.readValue(optionsJson, PublicKeyCredentialCreationOptions.class);
            PublicKeyCredential<AuthenticatorAttestationResponse> credential =
                    this.webAuthnMapper.readValue(credentialJson, ATTESTATION_TYPE);
            String safeLabel = StringUtils.hasText(label) ? label.trim() : "Android device";
            if (safeLabel.length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passkey label is too long.");
            }
            this.relyingPartyOperations.registerCredential(
                    new ImmutableRelyingPartyRegistrationRequest(
                            options, new RelyingPartyPublicKey(credential, safeLabel)));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The passkey registration response could not be verified.", exception);
        }
    }

    /**
     * Consumes the issued assertion challenge and verifies the assertion using
     * Spring Security's supported WebAuthn AuthenticationProvider.
     */
    public Authentication authenticate(String challengeId, String credentialJson) {
        requireConfigured();
        String optionsJson = consumeChallenge(challengeId, AUTHENTICATE, null);
        try {
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
        } catch (Exception exception) {
            // Treat unknown, expired, replayed and invalid assertions identically.
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
    }

    private NativePasskeyChallengeResponse issueChallenge(String purpose, String principalName, Object options) {
        try {
            // Keep the challenge single-use. Opportunistically prune expired rows so
            // anonymous requests do not accumulate expired ceremony state forever.
            this.jdbcTemplate.update(
                    "DELETE FROM native_passkey_challenges WHERE expires_at <= CURRENT_TIMESTAMP");
            String optionsJson = this.webAuthnMapper.writeValueAsString(options);
            UUID challengeId = UUID.randomUUID();
            this.jdbcTemplate.update("""
                    INSERT INTO native_passkey_challenges
                        (challenge_id, purpose, principal_name, options_json, expires_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                    """, challengeId, purpose, principalName, optionsJson);
            JsonNode optionsNode = this.webAuthnMapper.readTree(optionsJson);
            return new NativePasskeyChallengeResponse(challengeId.toString(), optionsNode);
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
            // DELETE ... RETURNING is the atomic replay and expiry gate.
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
    }

    private void requireEnabledUser(String username) {
        UserDetails account;
        try {
            account = this.userDetailsService.loadUserByUsername(username);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The account is not eligible to register a passkey.");
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
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(this.rpId) || this.allowedOrigins.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Native passkeys are not enabled until the relying-party ID and exact Android signing origin are configured.");
        }
    }

    private static Set<String> parseSet(String csv) {
        if (!StringUtils.hasText(csv)) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
