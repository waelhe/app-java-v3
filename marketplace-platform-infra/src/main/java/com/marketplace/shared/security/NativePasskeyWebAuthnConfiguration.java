package com.marketplace.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;

/**
 * Uses Spring Security's documented JDBC-backed WebAuthn repositories so
 * credentials and sign-counter updates survive process restarts and replicas.
 */
@Configuration(proxyBeanMethods = false)
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
}
