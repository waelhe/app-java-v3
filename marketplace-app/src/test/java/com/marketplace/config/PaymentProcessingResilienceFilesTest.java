package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the paymentProcessing channel's honest health (agent
 * findings 2026-10-26T13:11 export, row 24): the shared provider-health
 * circuit and its retry twin must keep the guarded method's own
 * caller-controlled failures OUT of their failure accounting —
 * {@code processIntent} fronts a lookup ({@code getIntentForUser} →
 * {@code ResourceNotFoundException} for an absent id, Spring Security's
 * {@code AccessDeniedException} for a foreign intent) and a state
 * validation ({@code markProcessing} → {@code ConflictException}) before
 * the PSP crossing, so an ordinary consumer hammering nonexistent intent
 * ids must never move shared payment availability for everyone.
 *
 * <p>Same class of latent "config that lies" defect as
 * {@code ObservationCoverageFilesTest} and the {@code aiChat} OFF-state
 * pin: nothing at runtime rejects an instance that silently loses its
 * {@code ignore-exceptions} list — the breaker just starts counting
 * expected business refusals as channel outages — only a pinned test
 * keeps the posture honest. The mechanism is Resilience4j's own
 * documented {@code ignore-exceptions} instance setting (the same tool
 * the {@code aiChat} instance uses for its OFF state): ignored
 * exceptions "should be ignored and neither count as failure nor
 * success", and the retry twin's list leaves them un-retried.</p>
 *
 * <p>W3 (CodeRabbit round on #827, 2026-11-14 — the F-827-1 twin from the
 * 2026-11-14 adversarial behavioral review): {@code requireChannel()}'s 503
 * SU-001 joins the same honest-state list on BOTH twins. The capability
 * being OFF is a state, not a channel failure (the {@code aiChat} OFF-state
 * precedent's own rule); an unbound channel cannot become bound between
 * attempts, so the retry would only burn ~3s of backoff before the identical
 * 503 on the consumer money path, and the SHARED breaker must keep measuring
 * only the bound channel's real health — otherwise its open state rejects
 * the healthy {@code refundPayment}/{@code confirm}/{@code autoRefund}
 * paths for the 60s open window.</p>
 */
class PaymentProcessingResilienceFilesTest {

    private static final List<String> HONEST_STATE_FAILURES = List.of(
            "com.marketplace.shared.api.ResourceNotFoundException",
            "org.springframework.security.access.AccessDeniedException",
            "com.marketplace.shared.api.ConflictException",
            "com.marketplace.shared.api.ServiceUnavailableException");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadResilience4j() throws IOException {
        Path yml = Paths.get("src/main/resources/application.yml");
        try (var in = Files.newInputStream(yml)) {
            Map<String, Object> root = new Yaml().load(in);
            return (Map<String, Object>) root.get("resilience4j");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> ignoreListOf(String family) throws IOException {
        Map<String, Object> resilience = loadResilience4j();
        Map<String, Object> kind = (Map<String, Object>) resilience.get(family);
        Map<String, Object> instances = (Map<String, Object>) kind.get("instances");
        Map<String, Object> instance = (Map<String, Object>) instances.get("paymentProcessing");
        return (List<String>) instance.get("ignore-exceptions");
    }

    @Test
    void theSharedPaymentCircuitIgnoresCallerControlledLookupAndValidationFailures()
            throws IOException {
        List<String> ignored = ignoreListOf("circuitbreaker");
        assertThat(ignored).isNotNull();
        assertThat(ignored).containsExactlyElementsOf(HONEST_STATE_FAILURES);
    }

    @Test
    void theSharedPaymentRetryLeavesTheExpectedFailuresUnRetried() throws IOException {
        List<String> ignored = ignoreListOf("retry");
        assertThat(ignored).isNotNull();
        assertThat(ignored).containsExactlyElementsOf(HONEST_STATE_FAILURES);
    }

    @Test
    void theUnboundChannel503RidesTheIgnoreMechanismOnBothTwins() throws IOException {
        // W3: requireChannel()'s ServiceUnavailableException (503 SU-001) must
        // stay on BOTH lists — dropping either entry resurrects the measured
        // defect: ~3s of retry backoff before the identical 503 and a shared
        // breaker open that rejects healthy refund/confirm/autoRefund paths.
        assertThat(ignoreListOf("circuitbreaker"))
                .contains("com.marketplace.shared.api.ServiceUnavailableException");
        assertThat(ignoreListOf("retry"))
                .contains("com.marketplace.shared.api.ServiceUnavailableException");
    }
}
