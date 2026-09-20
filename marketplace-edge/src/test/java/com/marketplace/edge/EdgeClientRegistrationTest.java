package com.marketplace.edge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D6 for the edge: a prod edge without EDGE_CLIENT_SECRET must fail fast at
 * startup — never fall back to an anonymous/secret-less client. Mirrors
 * {@code JwkSourceProdHardeningTest} (marketplace-platform-infra).
 */
class EdgeClientRegistrationTest {

    @Test
    void blankSecretThrows() {
        MockEnvironment env = new MockEnvironment().withProperty("EDGE_CLIENT_SECRET", "");

        assertThatThrownBy(() -> new EdgeSecurityConfig().edgeProdGuard(env)
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EDGE_CLIENT_SECRET");
    }

    @Test
    void missingSecretThrows() {
        MockEnvironment env = new MockEnvironment();

        assertThatThrownBy(() -> new EdgeSecurityConfig().edgeProdGuard(env)
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EDGE_CLIENT_SECRET");
    }

    @Test
    void presentSecretPasses() {
        MockEnvironment env = new MockEnvironment().withProperty("EDGE_CLIENT_SECRET", "s3cr3t-from-env");

        assertThatCode(() -> new EdgeSecurityConfig().edgeProdGuard(env)
                .run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }
}
