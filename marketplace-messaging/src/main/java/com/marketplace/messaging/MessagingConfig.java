package com.marketplace.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * L34 (realestate systems plan §5 — lead capture): registers the module's
 * {@link MessagingProperties} (the {@code MediaConfig} house pattern for
 * module-owned configuration).
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
class MessagingConfig {

    /**
     * House fail-fast-in-prod pattern ({@code JwkSourceProdHardening},
     * {@code MediaConfig}'s cleartext-endpoint guard — CodeRabbit #242
     * round 2): the IP fingerprint HMAC key exists to make the stored
     * digest non-enumerable (CWE-759); an unset key in production would
     * silently degrade the fingerprint to an empty-key HMAC — functional,
     * but weaker than the deployment promised. Fail the startup instead.
     */
    MessagingConfig(MessagingProperties properties, Environment environment) {
        if (properties.leads().ipHashKey().isBlank()
                && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "marketplace.messaging.leads.ip-hash-key must be set in the prod profile —"
                            + " the lead sender fingerprint is a keyed HMAC and an unset key"
                            + " silently weakens it (CWE-759)");
        }
    }
}
