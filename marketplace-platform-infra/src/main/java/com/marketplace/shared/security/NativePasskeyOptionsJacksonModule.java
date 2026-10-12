package com.marketplace.shared.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Deserializers for the two server-issued WebAuthn options types, layered on top of
 * Spring Security's {@link WebauthnJacksonModule}.
 *
 * <p>The official module registers mixins for {@link PublicKeyCredentialCreationOptions}
 * and {@link PublicKeyCredentialRequestOptions} that only <em>serialize</em> (no
 * {@code @JsonCreator}): the official repository contract keeps the options object in
 * the session, so the official stack never needs to read them back. The native Android
 * ceremonies are deliberately stateless (no {@code HttpSession}) and persist the issued
 * options as JSON in PostgreSQL via {@code native_passkey_challenges}, so the readback
 * leg needs these deserializers. Every value is rebuilt through the official builder
 * APIs of {@code org.springframework.security.web.webauthn.api}; only the Jackson
 * extension point ({@link SimpleModule}/{@link ValueDeserializer}) is ours.</p>
 *
 * <p>Client-input extensions are not reconstructed: the official serializer emits an
 * empty object and WebAuthn4J verification does not consume client-input extensions,
 * so an absent value is semantically identical for the ceremonies this module serves.</p>
 */
final class NativePasskeyOptionsJacksonModule extends SimpleModule {

    NativePasskeyOptionsJacksonModule() {
        super(NativePasskeyOptionsJacksonModule.class.getName());
        addDeserializer(PublicKeyCredentialCreationOptions.class, new CreationOptionsDeserializer());
        addDeserializer(PublicKeyCredentialRequestOptions.class, new RequestOptionsDeserializer());
    }

    private static final class CreationOptionsDeserializer
            extends ValueDeserializer<PublicKeyCredentialCreationOptions> {

        @Override
        public PublicKeyCredentialCreationOptions deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode node = context.readTree(parser);
            PublicKeyCredentialCreationOptions.PublicKeyCredentialCreationOptionsBuilder builder =
                    PublicKeyCredentialCreationOptions.builder()
                            .challenge(Bytes.fromBase64(required(node, "challenge")));
            JsonNode rp = node.path("rp");
            if (rp.isObject()) {
                builder.rp(PublicKeyCredentialRpEntity.builder()
                        .id(rp.path("id").asString(null))
                        .name(rp.path("name").asString(null))
                        .build());
            }
            JsonNode user = node.path("user");
            if (user.isObject()) {
                builder.user(ImmutablePublicKeyCredentialUserEntity.builder()
                        .id(Bytes.fromBase64(user.path("id").asString("")))
                        .name(user.path("name").asString(null))
                        .displayName(user.path("displayName").asString(null))
                        .build());
            }
            List<PublicKeyCredentialParameters> parameters = new ArrayList<>();
            JsonNode parameterArray = node.path("pubKeyCredParams");
            if (parameterArray.isArray()) {
                for (JsonNode parameter : parameterArray) {
                    PublicKeyCredentialParameters constant =
                            PARAMETERS_BY_ALGORITHM.get(parameter.path("alg").asLong(Long.MIN_VALUE));
                    if (constant != null) {
                        parameters.add(constant);
                    }
                }
            }
            if (!parameters.isEmpty()) {
                builder.pubKeyCredParams(parameters);
            }
            builder.excludeCredentials(descriptors(node.path("excludeCredentials")));
            long timeoutMillis = node.path("timeout").asLong(Long.MIN_VALUE);
            if (timeoutMillis > 0) {
                builder.timeout(Duration.ofMillis(timeoutMillis));
            }
            JsonNode selection = node.path("authenticatorSelection");
            if (selection.isObject()) {
                AuthenticatorSelectionCriteria.AuthenticatorSelectionCriteriaBuilder criteria =
                        AuthenticatorSelectionCriteria.builder()
                                .residentKey(residentKey(selection.path("residentKey").asString(null)))
                                .userVerification(userVerification(selection.path("userVerification").asString(null)));
                builder.authenticatorSelection(criteria.build());
            }
            return builder.build();
        }
    }

    private static final class RequestOptionsDeserializer
            extends ValueDeserializer<PublicKeyCredentialRequestOptions> {

        @Override
        public PublicKeyCredentialRequestOptions deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode node = context.readTree(parser);
            PublicKeyCredentialRequestOptions.PublicKeyCredentialRequestOptionsBuilder builder =
                    PublicKeyCredentialRequestOptions.builder()
                            .challenge(Bytes.fromBase64(required(node, "challenge")))
                            .rpId(node.path("rpId").asString(null));
            long timeoutMillis = node.path("timeout").asLong(Long.MIN_VALUE);
            if (timeoutMillis > 0) {
                builder.timeout(Duration.ofMillis(timeoutMillis));
            }
            builder.allowCredentials(descriptors(node.path("allowCredentials")));
            builder.userVerification(userVerification(node.path("userVerification").asString(null)));
            return builder.build();
        }
    }

    /**
     * The official parameters vocabulary is a fixed constant set; the JSON carries the
     * COSE algorithm number each constant wraps, so the map rebuilds it exactly.
     */
    private static final Map<Long, PublicKeyCredentialParameters> PARAMETERS_BY_ALGORITHM = Map.of(
            publicKeyCredentialAlg(PublicKeyCredentialParameters.EdDSA), PublicKeyCredentialParameters.EdDSA,
            publicKeyCredentialAlg(PublicKeyCredentialParameters.ES256), PublicKeyCredentialParameters.ES256,
            publicKeyCredentialAlg(PublicKeyCredentialParameters.ES384), PublicKeyCredentialParameters.ES384,
            publicKeyCredentialAlg(PublicKeyCredentialParameters.ES512), PublicKeyCredentialParameters.ES512,
            publicKeyCredentialAlg(PublicKeyCredentialParameters.RS256), PublicKeyCredentialParameters.RS256,
            publicKeyCredentialAlg(PublicKeyCredentialParameters.RS384), PublicKeyCredentialParameters.RS384,
            publicKeyCredentialAlg(PublicKeyCredentialParameters.RS512), PublicKeyCredentialParameters.RS512,
            publicKeyCredentialAlg(PublicKeyCredentialParameters.RS1), PublicKeyCredentialParameters.RS1);

    private static long publicKeyCredentialAlg(PublicKeyCredentialParameters parameter) {
        return parameter.getAlg().getValue();
    }

    private static List<PublicKeyCredentialDescriptor> descriptors(JsonNode array) {
        List<PublicKeyCredentialDescriptor> descriptors = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode element : array) {
                PublicKeyCredentialDescriptor.PublicKeyCredentialDescriptorBuilder descriptor =
                        PublicKeyCredentialDescriptor.builder()
                                .id(Bytes.fromBase64(element.path("id").asString("")))
                                .type(PublicKeyCredentialType.valueOf(element.path("type").asString("public-key")));
                descriptors.add(descriptor.build());
            }
        }
        return descriptors;
    }

    private static UserVerificationRequirement userVerification(String value) {
        if (value == null) {
            return null;
        }
        if (UserVerificationRequirement.PREFERRED.getValue().equals(value)) {
            return UserVerificationRequirement.PREFERRED;
        }
        if (UserVerificationRequirement.DISCOURAGED.getValue().equals(value)) {
            return UserVerificationRequirement.DISCOURAGED;
        }
        if (UserVerificationRequirement.REQUIRED.getValue().equals(value)) {
            return UserVerificationRequirement.REQUIRED;
        }
        return null;
    }

    private static ResidentKeyRequirement residentKey(String value) {
        if (value == null) {
            return null;
        }
        if (ResidentKeyRequirement.PREFERRED.getValue().equals(value)) {
            return ResidentKeyRequirement.PREFERRED;
        }
        if (ResidentKeyRequirement.DISCOURAGED.getValue().equals(value)) {
            return ResidentKeyRequirement.DISCOURAGED;
        }
        if (ResidentKeyRequirement.REQUIRED.getValue().equals(value)) {
            return ResidentKeyRequirement.REQUIRED;
        }
        return null;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asString(null);
        if (value == null) {
            throw new IllegalArgumentException("Missing required WebAuthn options field: " + field);
        }
        return value;
    }
}
