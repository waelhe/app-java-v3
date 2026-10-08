package com.marketplace.edge;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The binding-time transport gate (EdgeBackendProperties) — Boot's own
 * fail-fast mechanism, pinned with Boot's own test utility
 * ({@link ApplicationContextRunner}: "a lightweight way to test that the
 * application context starts up" — Boot reference, Testing). The four
 * measured postures:
 *
 * <ul>
 * <li>https — accepted (the production posture).</li>
 * <li>loopback http — accepted (local development; the traffic never
 * leaves the machine — CodeRabbit's own "explicit exception for trusted
 * internal addresses").</li>
 * <li>plaintext off-loopback WITHOUT the opt-in — the boot FAILS (the
 * CWE-319 gap the removed manual runner used to guard; now carried by
 * the framework's validation).</li>
 * <li>plaintext off-loopback WITH the explicit opt-in — accepted (the
 * measured, documented internal-backend escape hatch).</li>
 * </ul>
 */
class EdgeBackendPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProperties.class);

    @EnableConfigurationProperties(EdgeBackendProperties.class)
    static class EnableProperties {
    }

    @Test
    void httpsBackendBinds() {
        runner.withPropertyValues("edge.backend.url=https://api.example.com")
                .run(context -> {
                    assertBound(context);
                    assertThat(context.getBean(EdgeBackendProperties.class).url())
                            .isEqualTo(URI.create("https://api.example.com"));
                });
    }

    @Test
    void loopbackHttpBindsForLocalDevelopment() {
        runner.withPropertyValues("edge.backend.url=http://localhost:8080")
                .run(this::assertBound);
    }

    @Test
    void plaintextOffLoopbackFailsTheBoot() {
        runner.withPropertyValues("edge.backend.url=http://backend.internal.example.com:8080")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    org.springframework.boot.context.properties.bind.validation.BindValidationException.class)
                            .hasStackTraceContaining("must use https")
                            .hasStackTraceContaining("allow-insecure-transport");
                });
    }

    @Test
    void explicitOptInAcceptsPlaintextInternalBackend() {
        runner.withPropertyValues(
                        "edge.backend.url=http://backend.internal.example.com:8080",
                        "edge.backend.allow-insecure-transport=true")
                .run(this::assertBound);
    }

    private void assertBound(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(EdgeBackendProperties.class);
    }
}
