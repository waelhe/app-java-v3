package com.marketplace.shared.security;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativePasskeyServiceTest {

    private static final String USERNAME = "consumer@example.com";
    private static final String FINGERPRINT = "00:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:"
            + "10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final WebAuthnRelyingPartyOperations relyingPartyOperations =
            mock(WebAuthnRelyingPartyOperations.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final NativePasskeyService service = new NativePasskeyService(
            jdbcTemplate, relyingPartyOperations, userDetailsService, configuredProperties());

    @Test
    void createAuthenticationOptionsFailsClosedWhenNotConfigured() {
        NativePasskeyService unconfigured = new NativePasskeyService(
                jdbcTemplate, relyingPartyOperations, userDetailsService,
                new NativePasskeyProperties("", "Marketplace", List.of(),
                        "com.marketplace.android", List.of()));

        assertThatThrownBy(unconfigured::createAuthenticationOptions)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(503))
                .hasMessageContaining("not enabled");
    }

    @Test
    void createAuthenticationOptionsIssuesASingleUseChallenge() {
        when(relyingPartyOperations.createCredentialRequestOptions(any()))
                .thenReturn(PublicKeyCredentialRequestOptions.builder().rpId("example.com").build());

        NativePasskeyChallengeResponse response = service.createAuthenticationOptions();

        assertThat(UUID.fromString(response.challengeId())).isNotNull();
        assertThat(response.optionsJson()).contains("\"rpId\":\"example.com\"");

        ArgumentCaptor<Object> challengeId = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), challengeId.capture(), eq("AUTHENTICATE"),
                isNull(), anyString());
        assertThat(challengeId.getValue()).isInstanceOf(UUID.class);
    }

    @Test
    void createAuthenticationOptionsMapsPersistenceFailureToServiceUnavailable() {
        when(relyingPartyOperations.createCredentialRequestOptions(any()))
                .thenReturn(PublicKeyCredentialRequestOptions.builder().rpId("example.com").build());
        when(jdbcTemplate.update(anyString(), any(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.createAuthenticationOptions())
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(503))
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    void createRegistrationOptionsRequiresASignedInAccount() {
        Authentication anonymous = new TestingAuthenticationToken("anonymousUser", "n/a");

        assertThatThrownBy(() -> service.createRegistrationOptions(anonymous))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(401));
    }

    @Test
    void createRegistrationOptionsRequiresAnEligibleAccount() {
        Authentication signedIn = signedIn();
        UserDetails disabled = User.withUsername(USERNAME)
                .password("n/a").disabled(true).roles("CONSUMER").build();
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(disabled);

        assertThatThrownBy(() -> service.createRegistrationOptions(signedIn))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void createRegistrationOptionsBindsTheChallengeToThePrincipal() {
        Authentication signedIn = signedIn();
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(enabledUser());
        when(relyingPartyOperations.createPublicKeyCredentialCreationOptions(any()))
                .thenReturn(creationOptions());

        NativePasskeyChallengeResponse response = service.createRegistrationOptions(signedIn);

        assertThat(UUID.fromString(response.challengeId())).isNotNull();
        ArgumentCaptor<Object> challengeId = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), challengeId.capture(), eq("REGISTER"),
                eq(USERNAME), anyString());
        assertThat(challengeId.getValue()).isInstanceOf(UUID.class);
    }

    @Test
    void authenticateFailsClosedWithTheGrantErrorWhenNotConfigured() {
        NativePasskeyService unconfigured = new NativePasskeyService(
                jdbcTemplate, relyingPartyOperations, userDetailsService,
                new NativePasskeyProperties("", "Marketplace", List.of(),
                        "com.marketplace.android", List.of()));

        assertThatThrownBy(
                () -> unconfigured.authenticate(UUID.randomUUID().toString(), "{}"))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    void authenticateRejectsAMalformedChallengeId() {
        assertThatThrownBy(() -> service.authenticate("not-a-uuid", "{}"))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    void authenticateRejectsAnUnknownOrExpiredChallenge() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                any(UUID.class), eq("AUTHENTICATE")))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(
                () -> service.authenticate(UUID.randomUUID().toString(), "{}"))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    void authenticateRejectsAnUnparseableCredentialPayload() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                any(UUID.class), eq("AUTHENTICATE")))
                .thenReturn("{}");

        assertThatThrownBy(
                () -> service.authenticate(UUID.randomUUID().toString(), "not-json"))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, ex ->
                        assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    void completeRegistrationRejectsAnOversizedDeviceLabel() {
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(enabledUser());
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                any(UUID.class), eq("REGISTER"), eq(USERNAME)))
                .thenReturn(creationOptionsJson());

        assertThatThrownBy(() -> service.completeRegistration(
                UUID.randomUUID().toString(), parseableAttestationCredential(), "x".repeat(1001), signedIn()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("label is too long");
        verify(relyingPartyOperations, never()).registerCredential(any());
    }

    @Test
    void completeRegistrationMapsAnInvalidChallengeToBadRequest() {
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(enabledUser());
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                any(UUID.class), eq("REGISTER"), eq(USERNAME)))
                .thenThrow(new EmptyResultDataAccessException(1));

        // The registration path answers problem-detail 400 (RFC 9110 section 15.5.1),
        // not the OAuth 401 channel its sibling token grant uses — CodeRabbit round 1.
        assertThatThrownBy(() -> service.completeRegistration(
                UUID.randomUUID().toString(), "{}", null, signedIn()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("challenge is invalid or expired");
    }

    @Test
    void completeRegistrationWrapsUnverifiableResponsesInBadRequest() {
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(enabledUser());
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                any(UUID.class), eq("REGISTER"), eq(USERNAME)))
                .thenReturn(creationOptionsJson());

        assertThatThrownBy(() -> service.completeRegistration(
                UUID.randomUUID().toString(), "not-json", null, signedIn()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("could not be verified");
    }

    private static Authentication signedIn() {
        return new TestingAuthenticationToken(USERNAME, "n/a", "ROLE_CONSUMER");
    }

    private static UserDetails enabledUser() {
        return User.withUsername(USERNAME).password("n/a").roles("CONSUMER").build();
    }

    private static PublicKeyCredentialCreationOptions creationOptions() {
        return PublicKeyCredentialCreationOptions.builder()
                .rp(PublicKeyCredentialRpEntity.builder().id("example.com").name("Marketplace").build())
                .user(ImmutablePublicKeyCredentialUserEntity.builder()
                        .id(Bytes.random()).name(USERNAME).displayName("Consumer").build())
                .challenge(Bytes.random())
                .pubKeyCredParams(List.of(PublicKeyCredentialParameters.ES256))
                .build();
    }

    private static String creationOptionsJson() {
        try {
            return tools.jackson.databind.json.JsonMapper.builder()
                    .addModule(new org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule())
                    .build()
                    .writeValueAsString(creationOptions());
        } catch (Exception exception) {
            throw new IllegalStateException("fixture serialization failed", exception);
        }
    }

    /**
     * The official PublicKeyCredential deserializer requires id/rawId/type/response,
     * so the label gate is reached only with a structurally valid attestation payload.
     */
    private static String parseableAttestationCredential() {
        return "{\"id\":\"AAA\",\"rawId\":\"AAA\",\"type\":\"public-key\","
                + "\"response\":{\"clientDataJSON\":\"AAA\",\"attestationObject\":\"AAA\"}}";
    }

    private static NativePasskeyProperties configuredProperties() {
        byte[] digest = java.util.HexFormat.of().parseHex(FINGERPRINT.replace(":", ""));
        String origin = "android:apk-key-hash:"
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return new NativePasskeyProperties(
                "example.com", "Marketplace", List.of(origin), "com.marketplace.android",
                List.of(FINGERPRINT));
    }
}
