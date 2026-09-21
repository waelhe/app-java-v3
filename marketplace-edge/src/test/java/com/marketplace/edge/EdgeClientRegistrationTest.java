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
        // Dummy value follows the corpus idiom (it-*-secret, e.g.
        // "it-login-gate-secret" in marketplace-app ITs): deliberately LOW
        // entropy so gitleaks' generic-api-key rule (entropy >= 3.5) does not
        // flag it — the CI Build & Test gate runs gitleaks BEFORE Maven, so a
        // secret-shaped dummy kills the job before any test executes
        // (measured 2026-09-21: "s3cr3t-from-env" = 3.506891 -> leak found;
        // this value = ~3.2 -> clean, verified with gitleaks 8.24.3 + the
        // repo .gitleaks.toml on the exact commit range).
        MockEnvironment env = new MockEnvironment().withProperty("EDGE_CLIENT_SECRET", "it-edge-guard-secret");

        assertThatCode(() -> new EdgeSecurityConfig().edgeProdGuard(env)
                .run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void httpBackendThrowsInProd() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("EDGE_BACKEND_URL", "http://backend.internal:8080");

        assertThatThrownBy(() -> new EdgeSecurityConfig().edgeTransportGuard(env)
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    void httpsBackendPassesInProd() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("EDGE_BACKEND_URL", "https://backend.internal:8080");

        assertThatCode(() -> new EdgeSecurityConfig().edgeTransportGuard(env)
                .run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void explicitInsecureHatchPassesInProd() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("EDGE_BACKEND_URL", "http://backend.internal:8080")
                .withProperty("EDGE_BACKEND_ALLOW_INSECURE_TRANSPORT", "true");

        assertThatCode(() -> new EdgeSecurityConfig().edgeTransportGuard(env)
                .run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }
}
