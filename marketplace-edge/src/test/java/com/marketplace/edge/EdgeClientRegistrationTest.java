package com.marketplace.edge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D6 for the edge: a prod edge without EDGE_CLIENT_SECRET must fail fast at
 * startup — never fall back to an anonymous/secret-less client. Mirrors
 * {@code JwkSourceProdHardeningTest} (marketplace-platform-infra).
 *
 * <p>Union note 2026-10-08: the transport-guard trio (httpBackendThrows /
 * httpsBackendPasses / explicitInsecureHatchPasses) retired with the runner
 * itself — main's {@code EdgeBackendProperties} binding-time guard owns the
 * CWE-319 leg now and carries its own {@code EdgeBackendPropertiesTest}
 * (Boot's Binder utility, the official test idiom). This file pins the
 * surviving D6 secret guard only.</p>
 */
class EdgeClientRegistrationTest {

    /**
     * B.2 (compliance plan wave B — the production-parity sandbox): the D6
     * secret guard is declared active in {@code prod} AND {@code staging},
     * so a deployment that passes the sandbox exercises the exact fail-fast
     * posture production exercises. Pinned by annotation reflection so an
     * accidental profile narrowing regresses loudly. (The transport twin's
     * profile pin moved with it into EdgeBackendPropertiesTest — the
     * binding-time guard is profile-independent by design, every profile
     * boot validates it.)
     */
    @Test
    void theD6GuardRunsInProdAndStaging() throws Exception {
        java.lang.reflect.Method method = EdgeSecurityConfig.class
                .getDeclaredMethod("edgeProdGuard", org.springframework.core.env.Environment.class);
        Profile profile = method.getAnnotation(Profile.class);
        assertThat(profile).as("edgeProdGuard must declare @Profile").isNotNull();
        assertThat(profile.value())
                .as("edgeProdGuard activation profiles (prod + staging parity)")
                .containsExactlyInAnyOrder("prod", "staging");
    }

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
}
