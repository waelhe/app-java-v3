package com.marketplace.shared.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticationExtensionsClientInput;
import org.springframework.security.web.webauthn.api.AuthenticatorAttachment;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInput;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInputs;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stateless native ceremonies persist the server-issued options as JSON and read
 * them back. The official WebauthnJacksonModule serializes those types but carries no
 * deserializers for them (the official session-backed repositories never read them
 * back), so this roundtrip pins the builder-backed readback leg this module adds.
 *
 * <p>The official option types deliberately compare by identity for some nested
 * values (AuthenticatorSelectionCriteria has no equals), so the assertions walk the
 * semantic fields instead of whole-object equality.</p>
 */
class NativePasskeyOptionsJacksonModuleTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new WebauthnJacksonModule())
            .addModule(new NativePasskeyOptionsJacksonModule())
            .build();

    @Test
    void creationOptionsSurviveTheJsonRoundtrip() {
        PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.builder()
                .rp(PublicKeyCredentialRpEntity.builder().id("example.com").name("Marketplace").build())
                .user(ImmutablePublicKeyCredentialUserEntity.builder()
                        .id(Bytes.fromBase64("M2Aj8Q8_yEV-oriAmUuf9QI1jZEuAiV7yqGWsQBG5VU"))
                        .name("consumer@example.com")
                        .displayName("Consumer")
                        .build())
                .challenge(Bytes.fromBase64("slVCRZNYvkDAWvYB3XR8p-8etnAkfbw1om4vesfloZc"))
                .pubKeyCredParams(List.of(PublicKeyCredentialParameters.ES256))
                .timeout(Duration.ofMinutes(5))
                .excludeCredentials(List.of(PublicKeyCredentialDescriptor.builder()
                        .id(Bytes.fromBase64("AAA"))
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .transports(Set.of(AuthenticatorTransport.USB, AuthenticatorTransport.INTERNAL))
                        .build()))
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .authenticatorAttachment(AuthenticatorAttachment.PLATFORM)
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build())
                .attestation(AttestationConveyancePreference.DIRECT)
                .extensions(new ImmutableAuthenticationExtensionsClientInputs(
                        ImmutableAuthenticationExtensionsClientInput.credProps,
                        new ImmutableAuthenticationExtensionsClientInput<>("exampleExtension", Map.of("enabled", true))))
                .build();

        PublicKeyCredentialCreationOptions roundtrip = mapper.readValue(
                mapper.writeValueAsString(options), PublicKeyCredentialCreationOptions.class);

        assertThat(roundtrip.getChallenge()).isEqualTo(options.getChallenge());
        assertThat(roundtrip.getRp().getId()).isEqualTo("example.com");
        assertThat(roundtrip.getRp().getName()).isEqualTo("Marketplace");
        assertThat(roundtrip.getUser()).isEqualTo(options.getUser());
        assertThat(roundtrip.getPubKeyCredParams()).isEqualTo(options.getPubKeyCredParams());
        assertThat(roundtrip.getTimeout()).isEqualTo(options.getTimeout());
        assertThat(roundtrip.getExcludeCredentials()).hasSize(1);
        assertThat(roundtrip.getExcludeCredentials().getFirst().getTransports())
                .containsExactlyInAnyOrder(AuthenticatorTransport.USB, AuthenticatorTransport.INTERNAL);
        assertThat(roundtrip.getAuthenticatorSelection().getAuthenticatorAttachment())
                .isEqualTo(AuthenticatorAttachment.PLATFORM);
        assertThat(roundtrip.getAuthenticatorSelection().getResidentKey())
                .isEqualTo(ResidentKeyRequirement.REQUIRED);
        assertThat(roundtrip.getAuthenticatorSelection().getUserVerification())
                .isEqualTo(UserVerificationRequirement.REQUIRED);
        assertThat(roundtrip.getAttestation()).isEqualTo(AttestationConveyancePreference.DIRECT);
        assertThat(roundtrip.getExtensions().getInputs())
                .extracting(AuthenticationExtensionsClientInput::getExtensionId)
                .containsExactlyInAnyOrder("credProps", "exampleExtension");
        JsonNode extensionInput = (JsonNode) roundtrip.getExtensions().getInputs().stream()
                .filter(input -> "exampleExtension".equals(input.getExtensionId()))
                .findFirst().orElseThrow().getInput();
        assertThat(extensionInput.path("enabled").asBoolean()).isTrue();
    }

    @Test
    void requestOptionsSurviveTheJsonRoundtrip() {
        PublicKeyCredentialRequestOptions options = PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.fromBase64("OTpSSr50po9rux_ZVOZdrxlfD4FG2-xe6Mi22jg3QaY"))
                .rpId("example.com")
                .timeout(Duration.ofMinutes(2))
                .userVerification(UserVerificationRequirement.REQUIRED)
                .allowCredentials(List.of(PublicKeyCredentialDescriptor.builder()
                        .id(Bytes.fromBase64("AAA"))
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .transports(Set.of(AuthenticatorTransport.HYBRID))
                        .build()))
                .build();

        PublicKeyCredentialRequestOptions roundtrip = mapper.readValue(
                mapper.writeValueAsString(options), PublicKeyCredentialRequestOptions.class);

        assertThat(roundtrip.getChallenge()).isEqualTo(options.getChallenge());
        assertThat(roundtrip.getRpId()).isEqualTo("example.com");
        assertThat(roundtrip.getTimeout()).isEqualTo(options.getTimeout());
        assertThat(roundtrip.getUserVerification()).isEqualTo(UserVerificationRequirement.REQUIRED);
        assertThat(roundtrip.getAllowCredentials()).hasSize(1);
        assertThat(roundtrip.getAllowCredentials().get(0).getId()).isEqualTo(Bytes.fromBase64("AAA"));
        assertThat(roundtrip.getAllowCredentials().get(0).getType())
                .isEqualTo(PublicKeyCredentialType.PUBLIC_KEY);
        assertThat(roundtrip.getAllowCredentials().get(0).getTransports())
                .containsExactly(AuthenticatorTransport.HYBRID);
    }

    @Test
    void unsupportedAlgorithmFailsClosedInsteadOfBeingDropped() throws Exception {
        String json = mapper.writeValueAsString(creationOptions())
                .replace("\"alg\":-7", "\"alg\":-987654");

        assertThatThrownBy(() -> mapper.readValue(json, PublicKeyCredentialCreationOptions.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported WebAuthn COSE algorithm");
    }

    private static PublicKeyCredentialCreationOptions creationOptions() {
        return PublicKeyCredentialCreationOptions.builder()
                .rp(PublicKeyCredentialRpEntity.builder().id("example.com").name("Marketplace").build())
                .user(ImmutablePublicKeyCredentialUserEntity.builder()
                        .id(Bytes.fromBase64("M2Aj8Q8_yEV-oriAmUuf9QI1jZEuAiV7yqGWsQBG5VU"))
                        .name("consumer@example.com")
                        .displayName("Consumer")
                        .build())
                .challenge(Bytes.fromBase64("slVCRZNYvkDAWvYB3XR8p-8etnAkfbw1om4vesfloZc"))
                .pubKeyCredParams(List.of(PublicKeyCredentialParameters.ES256))
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    @Test
    void minimalOptionsRoundtripWithDefaultsIntact() {
        PublicKeyCredentialRequestOptions options = PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random())
                .rpId("example.com")
                .build();

        PublicKeyCredentialRequestOptions roundtrip = mapper.readValue(
                mapper.writeValueAsString(options), PublicKeyCredentialRequestOptions.class);

        assertThat(roundtrip.getChallenge()).isEqualTo(options.getChallenge());
        assertThat(roundtrip.getRpId()).isEqualTo("example.com");
        assertThat(roundtrip.getAllowCredentials()).isEmpty();
        // The official builder defaults the timeout to five minutes; the roundtrip
        // carries the same default instead of dropping it.
        assertThat(roundtrip.getTimeout()).isEqualTo(Duration.ofMinutes(5));
    }
}
