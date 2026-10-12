package com.marketplace.shared.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>The official relying-party implementation emits the {@code credProps} creation
 * extension by default and may include stored transports in credential descriptors.
 * Both are preserved, along with attestation and authenticator-attachment preferences,
 * so persisted options retain their WebAuthn meaning instead of relying on today's
 * particular defaults.</p>
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
            if (!rp.isObject()) {
                throw new IllegalArgumentException("Missing required WebAuthn options object: rp");
            }
            builder.rp(PublicKeyCredentialRpEntity.builder()
                    .id(required(rp, "id"))
                    .name(required(rp, "name"))
                    .build());

            JsonNode user = node.path("user");
            if (!user.isObject()) {
                throw new IllegalArgumentException("Missing required WebAuthn options object: user");
            }
            builder.user(ImmutablePublicKeyCredentialUserEntity.builder()
                    .id(Bytes.fromBase64(required(user, "id")))
                    .name(required(user, "name"))
                    .displayName(user.path("displayName").asString(null))
                    .build());
            List<PublicKeyCredentialParameters> parameters = new ArrayList<>();
            JsonNode parameterArray = node.path("pubKeyCredParams");
            if (!parameterArray.isArray() || parameterArray.isEmpty()) {
                throw new IllegalArgumentException("Missing required WebAuthn options array: pubKeyCredParams");
            }
            for (JsonNode parameter : parameterArray) {
                String type = required(parameter, "type");
                if (!PublicKeyCredentialType.PUBLIC_KEY.getValue().equals(type)) {
                    throw new IllegalArgumentException("Unsupported WebAuthn credential type: " + type);
                }
                JsonNode algorithmNode = parameter.path("alg");
                if (!algorithmNode.isNumber()) {
                    throw new IllegalArgumentException("WebAuthn COSE algorithm must be numeric");
                }
                long algorithm = algorithmNode.asLong(Long.MIN_VALUE);
                PublicKeyCredentialParameters constant = PARAMETERS_BY_ALGORITHM.get(algorithm);
                if (constant == null) {
                    throw new IllegalArgumentException("Unsupported WebAuthn COSE algorithm: " + algorithm);
                }
                parameters.add(constant);
            }
            builder.pubKeyCredParams(parameters);
            builder.excludeCredentials(descriptors(node.path("excludeCredentials")));
            String attestation = optionalString(node, "attestation");
            if (attestation != null) {
                builder.attestation(AttestationConveyancePreference.valueOf(attestation));
            }
            JsonNode extensions = node.path("extensions");
            if (!extensions.isMissingNode() && !extensions.isNull()) {
                if (!extensions.isObject()) {
                    throw new IllegalArgumentException("WebAuthn extensions must be an object");
                }
                builder.extensions(extensions(extensions));
            }
            Duration timeout = optionalTimeout(node);
            if (timeout != null) {
                builder.timeout(timeout);
            }
            JsonNode selection = node.path("authenticatorSelection");
            if (!selection.isMissingNode() && !selection.isNull()) {
                if (!selection.isObject()) {
                    throw new IllegalArgumentException("WebAuthn authenticatorSelection must be an object");
                }
                AuthenticatorSelectionCriteria.AuthenticatorSelectionCriteriaBuilder criteria =
                        AuthenticatorSelectionCriteria.builder()
                                .authenticatorAttachment(authenticatorAttachment(
                                        optionalString(selection, "authenticatorAttachment")))
                                .residentKey(residentKey(optionalString(selection, "residentKey")))
                                .userVerification(userVerification(optionalString(selection, "userVerification")));
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
                            .rpId(required(node, "rpId"));
            Duration timeout = optionalTimeout(node);
            if (timeout != null) {
                builder.timeout(timeout);
            }
            builder.allowCredentials(descriptors(node.path("allowCredentials")));
            builder.userVerification(userVerification(optionalString(node, "userVerification")));
            JsonNode extensions = node.path("extensions");
            if (!extensions.isMissingNode() && !extensions.isNull()) {
                if (!extensions.isObject()) {
                    throw new IllegalArgumentException("WebAuthn extensions must be an object");
                }
                builder.extensions(extensions(extensions));
            }
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
        if (array == null || array.isMissingNode() || array.isNull()) {
            return descriptors;
        }
        if (!array.isArray()) {
            throw new IllegalArgumentException("WebAuthn credential descriptors must be an array");
        }
        for (JsonNode element : array) {
            PublicKeyCredentialDescriptor.PublicKeyCredentialDescriptorBuilder descriptor =
                    PublicKeyCredentialDescriptor.builder()
                            .id(Bytes.fromBase64(required(element, "id")))
                            .type(PublicKeyCredentialType.valueOf(
                                    optionalString(element, "type") == null
                                            ? "public-key" : optionalString(element, "type")));
            JsonNode transports = element.path("transports");
            if (!transports.isMissingNode() && !transports.isNull()) {
                if (!transports.isArray()) {
                    throw new IllegalArgumentException("WebAuthn credential transports must be an array");
                }
                Set<AuthenticatorTransport> values = new HashSet<>();
                for (JsonNode transport : transports) {
                    if (!transport.isString()) {
                        throw new IllegalArgumentException("WebAuthn credential transport must be a string");
                    }
                    values.add(AuthenticatorTransport.valueOf(transport.asString()));
                }
                descriptor.transports(Set.copyOf(values));
            }
            descriptors.add(descriptor.build());
        }
        return descriptors;
    }

    private static Duration optionalTimeout(JsonNode node) {
        JsonNode timeout = node.path("timeout");
        if (timeout.isMissingNode() || timeout.isNull()) {
            return null;
        }
        if (!timeout.isNumber()) {
            throw new IllegalArgumentException("WebAuthn timeout must be numeric");
        }
        long millis = timeout.asLong(Long.MIN_VALUE);
        if (millis <= 0) {
            throw new IllegalArgumentException("WebAuthn timeout must be positive");
        }
        return Duration.ofMillis(millis);
    }

    private static String optionalString(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException("WebAuthn options field must be a string: " + field);
        }
        return value.asString();
    }

    private static ImmutableAuthenticationExtensionsClientInputs extensions(JsonNode node) {
        List<AuthenticationExtensionsClientInput> inputs = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            if ("credProps".equals(property.getKey()) && property.getValue().asBoolean(false)) {
                inputs.add(ImmutableAuthenticationExtensionsClientInput.credProps);
            } else {
                // Retain unknown extension payloads as JSON trees so their
                // serialized values survive persistence. The application does not
                // interpret extension-specific payloads it does not understand.
                inputs.add(new ImmutableAuthenticationExtensionsClientInput<>(
                        property.getKey(), property.getValue()));
            }
        }
        return new ImmutableAuthenticationExtensionsClientInputs(inputs);
    }

    private static AuthenticatorAttachment authenticatorAttachment(String value) {
        return value == null ? null : AuthenticatorAttachment.valueOf(value);
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
        // Spring Security 7.1.1 cannot model an unknown user-verification value.
        // Fail closed; never weaken a requirement by replacing it with null.
        throw new IllegalArgumentException("Unsupported WebAuthn user verification requirement: " + value);
    }

    private static ResidentKeyRequirement residentKey(String value) {
        return value == null ? null : ResidentKeyRequirement.valueOf(value);
    }

    private static String required(JsonNode node, String field) {
        String value = optionalString(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required WebAuthn options field: " + field);
        }
        return value;
    }
}
