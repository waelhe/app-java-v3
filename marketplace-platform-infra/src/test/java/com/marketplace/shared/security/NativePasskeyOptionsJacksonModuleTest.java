package com.marketplace.shared.security;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.Bytes;
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
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build())
                .build();

        PublicKeyCredentialCreationOptions roundtrip = mapper.readValue(
                mapper.writeValueAsString(options), PublicKeyCredentialCreationOptions.class);

        assertThat(roundtrip.getChallenge()).isEqualTo(options.getChallenge());
        assertThat(roundtrip.getRp().getId()).isEqualTo("example.com");
        assertThat(roundtrip.getRp().getName()).isEqualTo("Marketplace");
        assertThat(roundtrip.getUser()).isEqualTo(options.getUser());
        assertThat(roundtrip.getPubKeyCredParams()).isEqualTo(options.getPubKeyCredParams());
        assertThat(roundtrip.getTimeout()).isEqualTo(options.getTimeout());
        assertThat(roundtrip.getExcludeCredentials()).isEmpty();
        assertThat(roundtrip.getAuthenticatorSelection().getResidentKey())
                .isEqualTo(ResidentKeyRequirement.REQUIRED);
        assertThat(roundtrip.getAuthenticatorSelection().getUserVerification())
                .isEqualTo(UserVerificationRequirement.REQUIRED);
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
