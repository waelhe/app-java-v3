package com.marketplace.shared.security;

import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.util.StringUtils;

/**
 * Uses Spring Security's JDBC WebAuthn repositories and its official WebAuthn4J
 * relying-party operations. The same operations bean is shared by all passkey
 * ceremonies, so registration options, assertion validation, and credential
 * persistence use one set of relying-party settings.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NativePasskeyProperties.class)
public class NativePasskeyWebAuthnConfiguration {

    @Bean
    JdbcPublicKeyCredentialUserEntityRepository publicKeyCredentialUserEntityRepository(
            JdbcOperations jdbcOperations) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbcOperations);
    }

    @Bean
    JdbcUserCredentialRepository userCredentialRepository(JdbcOperations jdbcOperations) {
        return new JdbcUserCredentialRepository(jdbcOperations);
    }

    @Bean
    WebAuthnRelyingPartyOperations webAuthnRelyingPartyOperations(
            JdbcPublicKeyCredentialUserEntityRepository userEntities,
            JdbcUserCredentialRepository userCredentials,
            NativePasskeyProperties properties) {
        String rpId = StringUtils.hasText(properties.rpId()) ? properties.rpId() : "localhost";
        Webauthn4JRelyingPartyOperations operations = new Webauthn4JRelyingPartyOperations(
                userEntities,
                userCredentials,
                PublicKeyCredentialRpEntity.builder().id(rpId).name(properties.rpName()).build(),
                Set.copyOf(properties.allowedOrigins()));
        // WebAuthn options are produced by Spring Security. Require local user verification
        // and a discoverable credential instead of accepting a weaker authenticator policy.
        operations.setCustomizeCreationOptions(options ->
                options.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build()));
        operations.setCustomizeRequestOptions(options ->
                options.userVerification(UserVerificationRequirement.REQUIRED));
        return operations;
    }
}
